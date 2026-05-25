package com.plantcare.bot.service;

import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.observability.SentryTags;
import com.plantcare.bot.observability.SentryTags.Layer;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.telegram.RateLimitedTelegramSender;
import com.plantcare.bot.telegram.SendCallbacks;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Шедулер фото-прогресса (issue #72) — раз в час обходит растения с
 * включённым фото-прогрессом и шлёт push «пора обновить фото».
 *
 * <p>Идемпотентность достигается через {@code last_photo_prompt_sent_at}:
 * после успешной отправки дата фиксируется и фильтр {@code dedupBefore}
 * в репозитории исключает повторную отправку в течение
 * {@link #DEDUP_HOURS} часов.
 *
 * <p>Telegram-API не вызывается внутри той же транзакции, что и
 * выборка/обновление плана: сначала отдельная read-only транзакция
 * подбирает кандидатов, затем для каждого — отдельная транзакция на
 * обновление {@code last_photo_prompt_sent_at} после успешной отправки.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoProgressSchedulerService {

    /** Период тика — раз в час. */
    private static final long INTERVAL_MS = 3_600_000L;

    /** Окно дедупа: не повторять prompt чаще раза в N часов. */
    private static final int DEDUP_HOURS = 24;

    /** Окно вперёд, которое подбирает шедулер. */
    private static final int LOOK_AHEAD_HOURS = 1;

    private final PlantRepository plantRepository;
    private final PhotoProgressService photoProgressService;
    private final RateLimitedTelegramSender telegramSender;

    @Scheduled(fixedRate = INTERVAL_MS)
    public void checkPhotoPrompts() {
        // Issue #114: изолированный scope на тик — тег layer=scheduler не течёт на
        // соседние задачи shared-потока @Scheduled.
        SentryTags.runWithLayer(Layer.SCHEDULER, "PhotoProgressSchedulerService", () -> {
            LocalDateTime now = LocalDateTime.now();
            List<Plant> due = loadDuePlants(now);

            if (due.isEmpty()) {
                return;
            }
            log.info("Photo-progress tick: {} plants due", due.size());

            for (Plant plant : due) {
                try {
                    User user = plant.getUser();
                    if (user == null) {
                        continue;
                    }
                    if (user.isPaused() || user.isBlocked()) {
                        continue;
                    }
                    if (isQuietHours(user, now)) {
                        continue;
                    }

                    enqueuePhotoPrompt(user, plant);
                } catch (Exception e) {
                    log.error("Failed to handle photo-progress prompt for plant {}: {}",
                            plant.getId(), e.getMessage(), e);
                    Sentry.captureException(e);
                }
            }
        });
    }

    /**
     * Отдельная read-only транзакция: только подбираем кандидатов.
     *
     * <p>{@code plant.user} подтягивается через {@code JOIN FETCH} в самом
     * запросе, поэтому отдельная инициализация lazy-поля не нужна.
     */
    @Transactional(readOnly = true)
    public List<Plant> loadDuePlants(LocalDateTime now) {
        LocalDateTime until = now.plusHours(LOOK_AHEAD_HOURS);
        LocalDateTime dedupBefore = now.minusHours(DEDUP_HOURS);

        return plantRepository.findPhotoProgressDue(until, dedupBefore);
    }

    /**
     * Issue #29: отправка ушла в rate-limited очередь. Дедуп-пометка
     * {@code markPromptSent} (идемпотентное обновление {@code last_photo_prompt_sent_at})
     * переехала в onSuccess-колбэк на воркер-потоке. Захватываем только примитивы,
     * чтобы не тащить detached Plant/User между потоками.
     */
    private void enqueuePhotoPrompt(User user, Plant plant) {
        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("📸 Пора обновить фото для *" + escapeMd(plant.getName()) + "*")
                .parseMode("Markdown")
                .replyMarkup(buildPromptKeyboard(plant.getId()))
                .build();

        final long plantId = plant.getId();
        final long chatId = user.getTelegramChatId();
        telegramSender.enqueue(message, new SendCallbacks(
                () -> {
                    photoProgressService.markPromptSent(plantId, LocalDateTime.now());
                    log.info("Photo-progress prompt sent: plant={} chat={}", plantId, chatId);
                },
                null));
    }

    private InlineKeyboardMarkup buildPromptKeyboard(Long plantId) {
        InlineKeyboardButton add = InlineKeyboardButton.builder()
                .text("📸 Загрузить фото")
                .callbackData("PHOTO_PROGRESS:ADD:" + plantId)
                .build();
        InlineKeyboardButton skip = InlineKeyboardButton.builder()
                .text("⏭ Пропустить")
                .callbackData("PHOTO_PROGRESS:SKIP:" + plantId)
                .build();
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(add, skip))
                .build();
    }

    /**
     * Quiet-hours в local TZ юзера — копия логики
     * {@code NotificationSchedulerService.isQuietHours} (issue #28).
     */
    private boolean isQuietHours(User user, LocalDateTime now) {
        ZoneId userZone;
        try {
            userZone = ZoneId.of(user.getTimezone());
        } catch (Exception e) {
            userZone = ZoneId.of("UTC");
        }

        ZonedDateTime userNow = Instant.now().atZone(userZone);
        LocalTime userTime = userNow.toLocalTime();

        LocalTime start = user.getQuietHoursStart();
        LocalTime end = user.getQuietHoursEnd();
        if (start == null || end == null || start.equals(end)) {
            return false;
        }

        if (start.isBefore(end)) {
            return !userTime.isBefore(start) && userTime.isBefore(end);
        }
        return !userTime.isBefore(start) || userTime.isBefore(end);
    }

    private String escapeMd(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("`", "\\`");
    }
}

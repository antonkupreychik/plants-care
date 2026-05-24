package com.plantcare.bot.service;

import com.plantcare.bot.client.TelegramClientProvider;
import com.plantcare.bot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Hourly-шедулер годовщин растений (issue #117).
 *
 * <p>Каждый час в 5-ю минуту обходит активные растения с заполненной
 * {@code acquired_at}, выбирает те, у кого сегодня (в TZ юзера) календарная
 * годовщина, и отправляет push «🎂 Сегодня твоей {имя} N лет».
 *
 * <p>Шедулер запускается каждый час, но фактически шлёт только в утреннее
 * окно (09:00–10:00 локального времени юзера) — иначе годовщина прилетит в
 * 03:00 ночи юзеру в дальнем восточном часовом поясе.
 *
 * <p>Идемпотентность — на уровне БД ({@code plant_anniversaries_sent}, PK
 * по {@code (plant_id, anniversary_year)}): второй пуш на ту же годовщину
 * невозможен независимо от того, сколько раз шедулер сработает за день.
 *
 * <p>Telegram-вызовы намеренно вне транзакции к БД: сначала read-only
 * выборка кандидатов с расчётом контекста ({@link PlantAnniversaryService#findDueAnniversaries}),
 * затем отправка, затем отдельная транзакция на пометку «отправлено».
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlantAnniversaryScheduler {

    /** Час дня (локальный), с которого годовщина считается уместной. */
    private static final int MORNING_HOUR_START = 9;
    /** Час дня (локальный), до которого годовщина считается уместной (exclusive). */
    private static final int MORNING_HOUR_END = 10;

    private final PlantAnniversaryService plantAnniversaryService;
    private final UserRepository userRepository;
    private final TelegramClientProvider telegramClientProvider;
    private final Clock clock;

    /**
     * Cron в UTC: каждый час в 5-ю минуту. 5 минут смещение — чтобы не толкаться
     * с другими hourly-задачами (PhotoProgressSchedulerService, AcclimationSchedulerService),
     * которые стартуют в 0-ю минуту через fixedRate.
     */
    @Scheduled(cron = "0 5 * * * *", zone = "UTC")
    public void tick() {
        Instant now = clock.instant();
        List<PlantAnniversaryService.Anniversary> due =
                plantAnniversaryService.findDueAnniversaries(now);

        if (due.isEmpty()) {
            return;
        }
        log.info("Anniversary tick: {} candidates found", due.size());

        for (PlantAnniversaryService.Anniversary anniversary : due) {
            try {
                if (!isMorningInUserZone(anniversary.userId(), now)) {
                    continue;
                }
                if (sendAnniversary(anniversary)) {
                    plantAnniversaryService.markSent(
                            anniversary.plantId(),
                            anniversary.anniversaryYear(),
                            now);
                    log.info("Anniversary sent: plant={} years={} user={}",
                            anniversary.plantId(),
                            anniversary.yearsOld(),
                            anniversary.telegramChatId());
                }
            } catch (Exception e) {
                log.error("Failed to handle anniversary for plant {}: {}",
                        anniversary.plantId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Проверка «сейчас утро в TZ юзера». Юзер берётся свежий — на случай,
     * если он сменил TZ между snapshot'ом в Anniversary и моментом отправки.
     */
    private boolean isMorningInUserZone(Long userId, Instant now) {
        return userRepository.findById(userId)
                .map(user -> {
                    ZoneId zone = safeZone(user.getTimezone());
                    LocalTime localTime = now.atZone(zone).toLocalTime();
                    return !localTime.isBefore(LocalTime.of(MORNING_HOUR_START, 0))
                            && localTime.isBefore(LocalTime.of(MORNING_HOUR_END, 0));
                })
                .orElse(false);
    }

    private boolean sendAnniversary(PlantAnniversaryService.Anniversary anniversary) {
        SendMessage message = SendMessage.builder()
                .chatId(anniversary.telegramChatId().toString())
                .text(anniversary.buildText())
                .build();
        try {
            telegramClientProvider.getTelegramClient().execute(message);
            return true;
        } catch (TelegramApiException e) {
            log.error("Failed to send anniversary push (plant={}, chat={}): {}",
                    anniversary.plantId(), anniversary.telegramChatId(), e.getMessage());
            return false;
        }
    }

    private static ZoneId safeZone(String tz) {
        if (tz == null || tz.isBlank()) return ZoneOffset.UTC;
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            return ZoneOffset.UTC;
        }
    }
}

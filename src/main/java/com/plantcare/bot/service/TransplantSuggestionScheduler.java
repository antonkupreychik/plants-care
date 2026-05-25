package com.plantcare.bot.service;

import com.plantcare.core.service.QuietHoursPolicy;
import com.plantcare.core.service.TransplantSuggestionService;

import com.plantcare.core.config.TransplantSuggestionProperties;
import com.plantcare.core.observability.SentryTags;
import com.plantcare.core.observability.SentryTags.Layer;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.core.service.TransplantSuggestionService.DueSuggestion;
import com.plantcare.bot.telegram.RateLimitedTelegramSender;
import com.plantcare.bot.telegram.SendCallbacks;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Ежедневный шедулер подсказок расходников перед предстоящей пересадкой (issue #141).
 *
 * <p>Раз в день (09:00 UTC) подбирает растения, у которых предстоящая пересадка
 * (last TRANSPLANT + интервал из конфига) попадает в окно ближайших
 * {@code horizon-days} в TZ юзера, и шлёт мягкую нотификацию с кнопками
 * «➕ В список покупок» / «Не нужно».
 *
 * <p>Идемпотентность — на уровне БД ({@code transplant_supply_suggestions}, UNIQUE
 * по {@code (user_id, plant_id, source_event_id)}): повторный тик по той же
 * предстоящей пересадке не создаёт строку и не шлёт повтор.
 *
 * <p>Telegram-вызовы вне транзакции к БД: сервис в read-only выборке считает
 * кандидатов, затем строка-трекер создаётся в своей транзакции
 * ({@link TransplantSuggestionService#recordSuggested}), и только потом сообщение
 * уходит в rate-limited очередь. Уважаем quiet-hours юзера — мягкую подсказку
 * незачем слать ночью.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransplantSuggestionScheduler {

    static final String CALLBACK_PREFIX = "TRANSPLANT_SUGGEST";
    static final String ACTION_ADD = "ADD";
    static final String ACTION_SKIP = "SKIP";

    private final TransplantSuggestionService suggestionService;
    private final TransplantSuggestionProperties properties;
    private final QuietHoursPolicy quietHoursPolicy;
    private final UserRepository userRepository;
    private final RateLimitedTelegramSender telegramSender;
    private final java.time.Clock clock;

    /**
     * Cron в UTC: каждый день в 09:00. Мягкая подсказка, не привязанная к
     * минуте — daily-тика достаточно. Quiet-hours юзера всё равно проверяются
     * по его TZ перед отправкой.
     */
    @Scheduled(cron = "0 0 9 * * *", zone = "UTC")
    public void tick() {
        if (!properties.enabled()) {
            return;
        }

        SentryTags.runWithLayer(Layer.SCHEDULER, "TransplantSuggestionScheduler", () -> {
            Instant now = clock.instant();
            List<DueSuggestion> due = suggestionService.findDueSuggestions(now);

            if (due.isEmpty()) {
                return;
            }
            log.info("Transplant suggestion tick: {} candidates found", due.size());

            for (DueSuggestion suggestion : due) {
                try {
                    if (isQuietForUser(suggestion.userId(), now)) {
                        continue;
                    }
                    enqueueSuggestion(suggestion);
                } catch (Exception e) {
                    log.error("Failed to handle transplant suggestion for plant {}: {}",
                            suggestion.plantId(), e.getMessage(), e);
                    Sentry.captureException(e);
                }
            }
        });
    }

    /**
     * Quiet-hours проверяем по свежему юзеру (TZ мог измениться между выборкой
     * кандидатов и отправкой). Если юзер исчез — считаем «тихо», не шлём.
     */
    private boolean isQuietForUser(Long userId, Instant now) {
        return userRepository.findById(userId)
                .map(user -> quietHoursPolicy.isQuiet(user, now))
                .orElse(true);
    }

    /**
     * Создаёт строку-трекер (получая id для callback_data), затем кладёт
     * сообщение в очередь. id нужен ДО построения клавиатуры — поэтому
     * {@code recordSuggested} вызывается до enqueue, в отличие от анниверсари-
     * шедулера. Если строку уже создал параллельный тик — пропускаем.
     */
    private void enqueueSuggestion(DueSuggestion suggestion) {
        Optional<Long> suggestionId = suggestionService.recordSuggested(suggestion);
        if (suggestionId.isEmpty()) {
            return;
        }

        long id = suggestionId.get();
        SendMessage message = SendMessage.builder()
                .chatId(suggestion.telegramChatId().toString())
                .text(suggestionService.buildMessageText(suggestion.plantName()))
                .replyMarkup(buildKeyboard(id))
                .build();

        final long plantId = suggestion.plantId();
        final long chatId = suggestion.telegramChatId();
        telegramSender.enqueue(message, new SendCallbacks(
                () -> log.info("Transplant suggestion {} sent: plant={} chat={}",
                        id, plantId, chatId),
                null));
    }

    private InlineKeyboardMarkup buildKeyboard(long suggestionId) {
        InlineKeyboardButton addButton = InlineKeyboardButton.builder()
                .text("➕ В список покупок")
                .callbackData(CALLBACK_PREFIX + ":" + ACTION_ADD + ":" + suggestionId)
                .build();

        InlineKeyboardButton skipButton = InlineKeyboardButton.builder()
                .text("Не нужно")
                .callbackData(CALLBACK_PREFIX + ":" + ACTION_SKIP + ":" + suggestionId)
                .build();

        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(addButton, skipButton))
                .build();
    }
}

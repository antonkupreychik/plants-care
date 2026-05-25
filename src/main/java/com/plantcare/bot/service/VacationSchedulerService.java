package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.observability.SentryTags;
import com.plantcare.bot.observability.SentryTags.Layer;
import com.plantcare.bot.repository.CareScheduleRepository;
import com.plantcare.bot.repository.UserRepository;
import com.plantcare.bot.telegram.RateLimitedTelegramSender;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Hourly-тик режима отпуска (issue #53):
 *
 * <ol>
 *   <li>За ~24 часа до окончания отпуска шлёт пользователю напоминание
 *       «завтра возвращаюсь к напоминаниям» с кнопками продления +3 / +7 дней.
 *       Окно поиска полуоткрытое {@code [now+23h; now+24h)} — ровно один час,
 *       совпадающий с интервалом тика. Это даёт «exactly once» без дополнительной
 *       таблицы дедупа.</li>
 *   <li>Когда отпуск кончился ({@code paused_until ≤ now}) — шлёт welcome-back
 *       со списком просроченных задач (формат «За время отпуска накопилось…»)
 *       и обнуляет {@code paused_until}. Идемпотентно: повторный тик не подберёт
 *       уже сброшенного юзера.</li>
 * </ol>
 *
 * <p>Сбор данных (репозитории) идёт под {@code @Transactional}, отправка в Telegram —
 * вне открытой транзакции. См. CLAUDE.md.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VacationSchedulerService {

    /** Сколько просроченных задач максимум показываем в welcome-back. */
    static final int OVERDUE_LIST_LIMIT = 10;

    private final UserRepository userRepository;
    private final CareScheduleRepository careScheduleRepository;
    private final RateLimitedTelegramSender telegramSender;
    private final Clock clock;
    /**
     * Берём self из контекста, чтобы @Transactional-методы внутри одного бина
     * проходили через прокси (иначе self-invocation теряет транзакцию).
     */
    private final ApplicationContext applicationContext;

    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    public void tick() {
        // Issue #114: изолированный scope на тик — captureException внутри получит
        // тег layer=scheduler, и он не утечёт на соседние задачи shared-потока.
        SentryTags.runWithLayer(Layer.SCHEDULER, "VacationSchedulerService", () -> {
            LocalDateTime now = LocalDateTime.now(clock);
            VacationSchedulerService self = self();

            try {
                List<Long> chatIds = self.collectChatIdsForTomorrowReminder(now);
                for (Long chatId : chatIds) {
                    sendTomorrowBackMessage(chatId);
                }
            } catch (Exception e) {
                log.error("Failed to send tomorrow-back reminders: {}", e.getMessage(), e);
                Sentry.captureException(e);
            }

            try {
                List<WelcomeBackPayload> payloads = self.collectAndCloseVacations(now);
                for (WelcomeBackPayload payload : payloads) {
                    sendWelcomeBackMessage(payload);
                }
            } catch (Exception e) {
                log.error("Failed to process vacation ends: {}", e.getMessage(), e);
                Sentry.captureException(e);
            }
        });
    }

    @Transactional(readOnly = true)
    public List<Long> collectChatIdsForTomorrowReminder(LocalDateTime now) {
        LocalDateTime from = now.plus(Duration.ofHours(23));
        LocalDateTime to = now.plus(Duration.ofHours(24));

        List<User> users = userRepository.findVacationEndingBetween(from, to);
        List<Long> chatIds = new ArrayList<>(users.size());
        for (User user : users) {
            chatIds.add(user.getTelegramChatId());
        }

        if (!chatIds.isEmpty()) {
            log.info("Vacation tomorrow-back reminders due: {}", chatIds.size());
        }
        return chatIds;
    }

    @Transactional
    public List<WelcomeBackPayload> collectAndCloseVacations(LocalDateTime now) {
        List<User> users = userRepository.findVacationEnded(now);
        List<WelcomeBackPayload> result = new ArrayList<>(users.size());

        for (User user : users) {
            try {
                // Условный UPDATE: если юзер успел нажать «Вернуться сейчас» между
                // findVacationEnded и этим моментом, paused_until уже null, вернёт 0 —
                // welcome-back в таком случае не шлём (иначе дубль сообщения).
                int updated = userRepository.clearPausedUntilIfActive(user.getId());
                if (updated == 0) {
                    log.debug("Skip welcome-back for user {}: vacation already cleared", user.getTelegramChatId());
                    continue;
                }

                List<CareSchedule> overdue =
                        careScheduleRepository.findOverdueForUser(user.getId(), now);
                List<OverdueItem> items = toOverdueItems(overdue, now);

                result.add(new WelcomeBackPayload(user.getTelegramChatId(), items));
                log.info(
                        "Vacation ended for user {}: {} overdue tasks",
                        user.getTelegramChatId(), items.size()
                );
            } catch (Exception e) {
                log.error(
                        "Failed to close vacation for user {}: {}",
                        user.getTelegramChatId(), e.getMessage(), e
                );
                Sentry.captureException(e);
            }
        }
        return result;
    }

    private List<OverdueItem> toOverdueItems(List<CareSchedule> overdue, LocalDateTime now) {
        List<OverdueItem> items = new ArrayList<>(overdue.size());
        for (CareSchedule schedule : overdue) {
            long days = Duration.between(schedule.getNextDueAt(), now).toDays();
            int overdueDays = days > 0 ? (int) days : 0;
            items.add(new OverdueItem(
                    schedule.getPlant().getName(),
                    schedule.getTaskType(),
                    overdueDays
            ));
        }
        return items;
    }

    private void sendTomorrowBackMessage(Long chatId) {
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("+3 дня").callbackData("MENU:VACATION_EXTEND_3").build(),
                        InlineKeyboardButton.builder()
                                .text("+7 дней").callbackData("MENU:VACATION_EXTEND_7").build()
                )))
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text("🏖 Завтра возвращаюсь к напоминаниям. "
                        + "Если ещё не дома — продли:")
                .replyMarkup(keyboard)
                .build();

        // Issue #29: fire-and-forget через rate-limited очередь. Bookkeeping тут нет —
        // ошибки логируются и метрятся внутри очереди.
        telegramSender.enqueue(message);
        log.info("Tomorrow-back vacation reminder enqueued for chat {}", chatId);
    }

    private void sendWelcomeBackMessage(WelcomeBackPayload payload) {
        SendMessage message = SendMessage.builder()
                .chatId(payload.chatId().toString())
                .text(buildWelcomeBackText(payload.overdue()))
                .build();

        // Issue #29: fire-and-forget через rate-limited очередь.
        telegramSender.enqueue(message);
        log.info("Welcome-back enqueued for chat {} (overdue={})",
                payload.chatId(), payload.overdue().size());
    }

    private String buildWelcomeBackText(List<OverdueItem> overdue) {
        if (overdue.isEmpty()) {
            return "🌿 С возвращением! Возобновляю напоминания";
        }

        StringBuilder sb = new StringBuilder("🌿 С возвращением!\n\n");
        sb.append("За время отпуска накопилось:\n");

        int shown = Math.min(overdue.size(), OVERDUE_LIST_LIMIT);
        for (int i = 0; i < shown; i++) {
            OverdueItem item = overdue.get(i);
            sb.append("• ").append(item.plantName())
                    .append(" — ").append(taskLabel(item.taskType()));
            if (item.overdueDays() > 0) {
                sb.append(" (просрочено ").append(item.overdueDays())
                        .append(" ").append(pluralizeDays(item.overdueDays())).append(")");
            }
            sb.append("\n");
        }

        int remaining = overdue.size() - shown;
        if (remaining > 0) {
            sb.append("…и ещё ").append(remaining);
        }

        return sb.toString().trim();
    }

    /** Русское склонение: 1 день, 2 дня, 5 дней. */
    private String pluralizeDays(int n) {
        int mod10 = n % 10;
        int mod100 = n % 100;
        if (mod10 == 1 && mod100 != 11) return "день";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "дня";
        return "дней";
    }

    private String taskLabel(TaskType taskType) {
        return switch (taskType) {
            case WATERING -> "полить";
            case MISTING -> "опрыскать";
            case FERTILIZING -> "удобрить";
            case SOIL_CHECK -> "проверить грунт";
        };
    }

    private VacationSchedulerService self() {
        return applicationContext.getBean(VacationSchedulerService.class);
    }

    /** Полные данные для одного welcome-back сообщения. */
    public record WelcomeBackPayload(Long chatId, List<OverdueItem> overdue) {
    }

    /** Просроченная задача для строки списка. */
    public record OverdueItem(String plantName, TaskType taskType, int overdueDays) {
    }
}

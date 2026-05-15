package com.plantcare.bot.service;

import com.plantcare.bot.client.TelegramClientProvider;
import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.DigestTaskItem;
import com.plantcare.bot.domain.NotificationDigest;
import com.plantcare.bot.domain.NotificationLog;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.NotificationType;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareScheduleRepository;
import com.plantcare.bot.repository.NotificationDigestRepository;
import com.plantcare.bot.repository.NotificationLogRepository;
import com.plantcare.bot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationSchedulerService {

    private static final int DEDUP_HOURS = 12;

    private final CareScheduleRepository careScheduleRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final NotificationDigestRepository notificationDigestRepository;
    private final UserRepository userRepository;
    private final TelegramClientProvider telegramClientProvider;
    private final SchedulerHealthTracker schedulerHealthTracker;

    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void checkAndSendNotifications() {
        executeTick();
    }

    /**
     * Запустить tick синхронно из админ-панели (issue #59).
     * Возвращает количество расписаний, которые tick подобрал и попытался
     * обработать. Идемпотентность достигается за счёт дедупа в notifications_log
     * (12-часовое окно) — повторный ручной вызов вскоре после автоматического
     * не приведёт к двойным пушам.
     */
    @Transactional
    public int triggerManually() {
        log.info("Manual scheduler tick triggered (admin panel)");
        return executeTick();
    }

    /**
     * Отправить пуш по одному конкретному расписанию вручную из админки (issue #59).
     *
     * <p>Поведение зависит от {@code force}:
     * <ul>
     *   <li>{@code force=false} — применяются все обычные фильтры:
     *       {@code user.paused}, {@code user.blocked}, {@code plant.archived},
     *       quiet-hours и 12-часовой дедуп. Это «отправить как обычный шедулер».</li>
     *   <li>{@code force=true} — обходит pause/quiet/dedup. Архивированное растение
     *       и заблокированный юзер всё равно не получат push (некуда отправлять).
     *       Это «диагностический пинок».</li>
     * </ul>
     *
     * <p>В случае успеха продвигает {@code next_due_at} на следующий тик
     * (как обычный шедулер) и пишет в {@code notifications_log}.
     */
    @Transactional
    public SendOneResult sendOneSchedule(Long scheduleId, boolean force) {
        CareSchedule schedule = careScheduleRepository.findById(scheduleId).orElse(null);
        if (schedule == null) {
            return SendOneResult.notFound();
        }
        if (!schedule.isActive()) {
            return SendOneResult.skipped("Расписание неактивно");
        }
        Plant plant = schedule.getPlant();
        if (plant.isArchived()) {
            return SendOneResult.skipped("Растение архивировано");
        }
        User user = plant.getUser();
        if (user.isBlocked()) {
            return SendOneResult.skipped("Юзер заблокирован");
        }

        if (!force && !shouldSend(schedule, LocalDateTime.now())) {
            return SendOneResult.skipped("Заблокировано фильтром (пауза/quiet-hours/дедуп)");
        }

        try {
            sendNotification(user, plant, schedule);
            // Продвигаем next_due_at на следующий тик, как обычный шедулер.
            schedule.setNextDueAt(LocalDateTime.now().plusDays(schedule.getIntervalDays()));
            careScheduleRepository.save(schedule);
            return SendOneResult.sent();
        } catch (Exception e) {
            log.error("sendOneSchedule failed for schedule={}: {}", scheduleId, e.getMessage(), e);
            return SendOneResult.failed(e.getMessage());
        }
    }

    /**
     * Пропустить ближайший пуш по расписанию (issue #59) — продвигает
     * {@code next_due_at} на {@code +intervalDays}, не пишет {@code CareHistory}.
     * Это та же семантика, что у кнопки «Пропустить» в боте.
     */
    @Transactional
    public boolean skipOneSchedule(Long scheduleId) {
        CareSchedule schedule = careScheduleRepository.findById(scheduleId).orElse(null);
        if (schedule == null) return false;
        schedule.setNextDueAt(LocalDateTime.now().plusDays(schedule.getIntervalDays()));
        careScheduleRepository.save(schedule);
        log.info("Schedule {} skipped from admin, new next_due_at={}",
                scheduleId, schedule.getNextDueAt());
        return true;
    }

    /** Результат {@link #sendOneSchedule}. */
    public record SendOneResult(Status status, String reason) {
        public enum Status { SENT, SKIPPED, NOT_FOUND, FAILED }

        public static SendOneResult sent()                 { return new SendOneResult(Status.SENT, null); }
        public static SendOneResult skipped(String reason) { return new SendOneResult(Status.SKIPPED, reason); }
        public static SendOneResult notFound()             { return new SendOneResult(Status.NOT_FOUND, null); }
        public static SendOneResult failed(String reason)  { return new SendOneResult(Status.FAILED, reason); }

        public boolean isSent()      { return status == Status.SENT; }
        public boolean isSkipped()   { return status == Status.SKIPPED; }
        public boolean isNotFound()  { return status == Status.NOT_FOUND; }
        public boolean isFailed()    { return status == Status.FAILED; }
    }

    /**
     * Вся бизнес-логика тика. Вызывается из @Scheduled-обёртки и из ручного
     * триггера админ-панели. Возвращает число найденных due-расписаний
     * (то, что попало в очередь обработки до фильтров shouldSend и pause).
     */
    private int executeTick() {
        LocalDateTime now = LocalDateTime.now();
        List<CareSchedule> dueSchedules = careScheduleRepository.findDueSchedules(now);
        int dueCount = dueSchedules.size();

        Map<Long, List<CareSchedule>> schedulesByUser = new LinkedHashMap<>();

        // SOIL_CHECK всегда отправляется отдельным пушем (issue #74) — у него своя
        // логика ответов (DRY/WET/UNKNOWN), которая не вписывается в "Сделал всё".
        List<CareSchedule> standaloneSoilChecks = new ArrayList<>();

        // WATERING для растений в режиме акклиматизации (issue #75) тоже отдельно —
        // у них мягкий «проверь грунт» промпт с тремя вариантами, который не вписывается
        // в дайджест.
        List<CareSchedule> standaloneAcclimWaterings = new ArrayList<>();

        for (CareSchedule schedule : dueSchedules) {
            try {
                if (!shouldSend(schedule, now)) {
                    continue;
                }
                if (schedule.getTaskType() == TaskType.SOIL_CHECK) {
                    standaloneSoilChecks.add(schedule);
                    continue;
                }
                if (schedule.getTaskType() == TaskType.WATERING
                        && schedule.getPlant().isInAcclimation(now)) {
                    standaloneAcclimWaterings.add(schedule);
                    continue;
                }
                User user = schedule.getPlant().getUser();
                schedulesByUser
                        .computeIfAbsent(user.getId(), ignored -> new ArrayList<>())
                        .add(schedule);
            } catch (Exception e) {
                log.error("Error checking schedule id={}: {}", schedule.getId(), e.getMessage(), e);
            }
        }

        for (CareSchedule soilCheck : standaloneSoilChecks) {
            try {
                sendNotification(soilCheck.getPlant().getUser(), soilCheck.getPlant(), soilCheck);
            } catch (Exception e) {
                log.error("Error sending soil-check notification: {}", e.getMessage(), e);
            }
        }

        for (CareSchedule acclim : standaloneAcclimWaterings) {
            try {
                sendNotification(acclim.getPlant().getUser(), acclim.getPlant(), acclim);
            } catch (Exception e) {
                log.error("Error sending acclimation watering notification: {}", e.getMessage(), e);
            }
        }

        for (List<CareSchedule> schedules : schedulesByUser.values()) {
            try {
                if (schedules.size() == 1) {
                    CareSchedule schedule = schedules.get(0);
                    sendNotification(schedule.getPlant().getUser(), schedule.getPlant(), schedule);
                } else {
                    sendDigest(schedules.get(0).getPlant().getUser(), schedules);
                }
            } catch (Exception e) {
                log.error("Error sending notifications group: {}", e.getMessage(), e);
            }
        }

        // Фиксируем успешное завершение тика для healthcheck (issue #28).
        // Запись делается в самом конце: если до сюда не дошли (например, БД отвалилась
        // при загрузке dueSchedules или тик упал в неожиданном RuntimeException) —
        // таймстемп не обновится, и через max-tick-age health indicator вернёт DOWN.
        // AtomicReference.set() не участвует в JPA-транзакции, так что rollback
        // окружающего @Transactional на эту запись не повлияет.
        schedulerHealthTracker.recordTick();
        return dueCount;
    }

    private boolean shouldSend(CareSchedule schedule, LocalDateTime now) {
        Plant plant = schedule.getPlant();
        User user = plant.getUser();

        if (user.isPaused()) {
            return false;
        }

        if (isQuietHours(user, now)) {
            return false;
        }

        LocalDateTime deduplicationCutoff = now.minusHours(DEDUP_HOURS);

        return !notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(
                plant.getId(),
                schedule.getTaskType(),
                deduplicationCutoff
        );
    }

    private boolean isQuietHours(User user, LocalDateTime now) {
        ZoneId userZone;

        try {
            userZone = ZoneId.of(user.getTimezone());
        } catch (Exception e) {
            log.warn("Invalid timezone '{}' for user {}, defaulting to UTC",
                    user.getTimezone(), user.getTelegramChatId());
            userZone = ZoneId.of("UTC");
        }

        // Берём абсолютный момент через Instant.now() — независимо от JVM-зоны.
        // Раньше брали now.atZone(systemDefault()), что предполагало JVM=UTC. При
        // случайной переустановке TZ контейнера (TZ=Europe/Moscow на docker run)
        // quiet-hours смещались бы на величину этой зоны. Instant.now() это исключает.
        ZonedDateTime userNow = Instant.now().atZone(userZone);
        LocalTime userTime = userNow.toLocalTime();

        LocalTime start = user.getQuietHoursStart();
        LocalTime end = user.getQuietHoursEnd();

        if (start.equals(end)) {
            return false;
        }

        if (start.isBefore(end)) {
            return !userTime.isBefore(start) && userTime.isBefore(end);
        }

        return !userTime.isBefore(start) || userTime.isBefore(end);
    }

    private void sendNotification(User user, Plant plant, CareSchedule schedule) {
        boolean inAcclimation = plant.isInAcclimation(LocalDateTime.now())
                && schedule.getTaskType() == TaskType.WATERING;

        String text = inAcclimation
                ? buildAcclimationWateringText(plant)
                : buildNotificationText(plant, schedule.getTaskType());
        InlineKeyboardMarkup keyboard = inAcclimation
                ? buildAcclimationSoilCheckKeyboard(schedule.getId())
                : buildKeyboard(schedule.getId(), schedule.getTaskType());

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(text)
                .replyMarkup(keyboard)
                .build();

        try {
            telegramClientProvider.getTelegramClient().execute(message);
            saveNotificationLog(plant, schedule.getTaskType());

            log.info("Sent notification for plant '{}' id={} to user {} (acclimation={})",
                    plant.getName(), plant.getId(), user.getTelegramChatId(), inAcclimation);
        } catch (TelegramApiException e) {
            handleTelegramError(user, e);
        }
    }

    private String buildAcclimationWateringText(Plant plant) {
        return "💧 По плану сегодня полив: " + plant.getName() + ".\n"
                + "Проверь грунт — сухо на 2–3 см?";
    }

    private InlineKeyboardMarkup buildAcclimationSoilCheckKeyboard(Long scheduleId) {
        InlineKeyboardButton dry = InlineKeyboardButton.builder()
                .text("✅ Сухо")
                .callbackData("v1:accl_soil:" + scheduleId + ":DRY")
                .build();
        InlineKeyboardButton wet = InlineKeyboardButton.builder()
                .text("❌ Влажно")
                .callbackData("v1:accl_soil:" + scheduleId + ":WET")
                .build();
        InlineKeyboardButton unk = InlineKeyboardButton.builder()
                .text("🤷 Не знаю")
                .callbackData("v1:accl_soil:" + scheduleId + ":UNKNOWN")
                .build();
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(dry, wet, unk))
                .build();
    }

    private void sendDigest(User user, List<CareSchedule> schedules) {
        List<DigestTaskItem> items = schedules.stream()
                .map(schedule -> new DigestTaskItem(
                        schedule.getId(),
                        schedule.getPlant().getId(),
                        schedule.getPlant().getName(),
                        schedule.getTaskType(),
                        schedule.getNextDueAt()
                ))
                .toList();

        NotificationDigest digest = NotificationDigest.builder()
                .userId(user.getId())
                .plantTaskIds(items)
                .build();

        NotificationDigest savedDigest = notificationDigestRepository.save(digest);

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(buildDigestText(items))
                .replyMarkup(buildDigestKeyboard(savedDigest.getId()))
                .build();

        try {
            telegramClientProvider.getTelegramClient().execute(message);

            for (CareSchedule schedule : schedules) {
                saveNotificationLog(schedule.getPlant(), schedule.getTaskType());
            }

            log.info("Sent digest id={} with {} tasks to user {}",
                    savedDigest.getId(), schedules.size(), user.getTelegramChatId());
        } catch (TelegramApiException e) {
            handleTelegramError(user, e);
        }
    }

    private void saveNotificationLog(Plant plant, TaskType taskType) {
        NotificationLog logEntry = NotificationLog.builder()
                .plant(plant)
                .taskType(taskType)
                .notificationType(NotificationType.DUE)
                .sentAt(LocalDateTime.now())
                .build();

        notificationLogRepository.save(logEntry);
    }

    private String buildDigestText(List<DigestTaskItem> items) {
        StringBuilder builder = new StringBuilder("На сегодня:\n");

        for (DigestTaskItem item : items) {
            builder.append("• ")
                    .append(item.plantName())
                    .append(" — ")
                    .append(taskLabel(item.taskType()))
                    .append("\n");
        }

        return builder.toString().trim();
    }

    private InlineKeyboardMarkup buildDigestKeyboard(Long digestId) {
        InlineKeyboardButton doneAllButton = InlineKeyboardButton.builder()
                .text("✅ Сделал всё")
                .callbackData("digest:done_all:" + digestId)
                .build();

        InlineKeyboardButton expandButton = InlineKeyboardButton.builder()
                .text("По одному")
                .callbackData("digest:expand:" + digestId)
                .build();

        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(doneAllButton, expandButton))
                .build();
    }

    private String buildNotificationText(Plant plant, TaskType taskType) {
        return switch (taskType) {
            case WATERING -> "Пора полить: " + plant.getName();
            case MISTING -> "Пора опрыскать: " + plant.getName();
            case FERTILIZING -> "Пора удобрить: " + plant.getName();
            case SOIL_CHECK -> "🪴 Проверь грунт у " + plant.getName() + ". Земля сухая?";
        };
    }

    private InlineKeyboardMarkup buildKeyboard(Long scheduleId, TaskType taskType) {
        // SOIL_CHECK не имеет "Сделал/Отложить/Пропустить" — у него три варианта результата.
        if (taskType == TaskType.SOIL_CHECK) {
            return buildSoilCheckKeyboard(scheduleId);
        }

        String doneBtnLabel = switch (taskType) {
            case WATERING -> "✅ Полил";
            case MISTING -> "✅ Опрыскал";
            case FERTILIZING -> "✅ Удобрил";
            case SOIL_CHECK -> "✅ Проверил"; // unreachable, exhaustive switch
        };

        InlineKeyboardButton doneBtn = InlineKeyboardButton.builder()
                .text(doneBtnLabel)
                .callbackData("v1:done:" + scheduleId)
                .build();

        InlineKeyboardButton snoozeBtn = InlineKeyboardButton.builder()
                .text("⏰ Через 2 часа")
                .callbackData("v1:snooze:" + scheduleId)
                .build();

        InlineKeyboardButton skipBtn = InlineKeyboardButton.builder()
                .text("❌ Пропустить")
                .callbackData("v1:skip:" + scheduleId)
                .build();

        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(doneBtn, snoozeBtn, skipBtn))
                .build();
    }

    private InlineKeyboardMarkup buildSoilCheckKeyboard(Long scheduleId) {
        InlineKeyboardButton dryBtn = InlineKeyboardButton.builder()
                .text("✅ Сухая")
                .callbackData("v1:soil_dry:" + scheduleId)
                .build();

        InlineKeyboardButton wetBtn = InlineKeyboardButton.builder()
                .text("❌ Влажная")
                .callbackData("v1:soil_wet:" + scheduleId)
                .build();

        InlineKeyboardButton unkBtn = InlineKeyboardButton.builder()
                .text("🤷 Не знаю")
                .callbackData("v1:soil_unk:" + scheduleId)
                .build();

        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(dryBtn, wetBtn, unkBtn))
                .build();
    }

    private String taskLabel(TaskType taskType) {
        return switch (taskType) {
            case WATERING -> "полить";
            case MISTING -> "опрыскать";
            case FERTILIZING -> "удобрить";
            case SOIL_CHECK -> "проверить грунт";
        };
    }

    private void handleTelegramError(User user, TelegramApiException e) {
        String errorMessage = e.getMessage();

        if (errorMessage != null && errorMessage.contains("403")) {
            log.warn("Bot blocked by user {}, marking as blocked", user.getTelegramChatId());
            user.setBlocked(true);
            userRepository.save(user);
        } else {
            log.error("Failed to send notification to user {}: {}",
                    user.getTelegramChatId(), errorMessage, e);
        }
    }
}
package com.plantcare.bot.admin.scheduler.service;

import com.plantcare.bot.admin.config.AdminProperties;
import com.plantcare.bot.admin.dashboard.dto.SchedulerHealth;
import com.plantcare.bot.admin.dashboard.health.SchedulerHealthProvider;
import com.plantcare.bot.admin.scheduler.dto.QueuedNotificationDto;
import com.plantcare.bot.admin.scheduler.dto.SchedulerPageDto;
import com.plantcare.bot.admin.scheduler.repository.AdminSchedulerRepository;
import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.bot.service.NotificationSchedulerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Логика страницы /admin/scheduler (issue #59):
 *   1) собрать снепшот для рендера ({@link #buildPage()}),
 *   2) ручной триггер тика с защитой от двойного клика ({@link #triggerTick()}).
 *
 * <p>Защита от двойного клика — 30 сек cooldown, реализован на стороне сервера
 * через {@code AtomicReference<Instant>}. Клиентский disable кнопки (JS) — это
 * UX-стратегия, но без серверной защиты он лопается при F5 / параллельной вкладке.
 */
@Service
@Slf4j
public class AdminSchedulerService {

    /** Минимальный интервал между ручными триггерами, в секундах. */
    public static final long TRIGGER_COOLDOWN_SECONDS = 30;

    /** Окно «ближайшего будущего» для очереди уведомлений, в часах. */
    public static final int QUEUE_HORIZON_HOURS = 24;

    /** Жёсткий лимит на длину очереди в UI — защита от 10k+ строк. */
    public static final int QUEUE_LIMIT = 200;

    private final AdminSchedulerRepository repository;
    private final SchedulerHealthProvider healthProvider;
    private final NotificationSchedulerService schedulerService;
    private final CareScheduleRepository careScheduleRepository;

    /**
     * TZ, в которой админу показываются часы/даты на /admin/scheduler.
     * Берётся из {@code admin.dashboard.timezone}; если не задано —
     * системный default (для прод-сервера на UTC получится UTC, и админ
     * видит UTC же — это лучше, чем притворяться, что у нас локальное время).
     */
    private final ZoneId adminZone;

    /**
     * Last successful manual trigger timestamp. Используется для cooldown.
     * {@code null} = триггер ни разу не нажимали в этой жизни процесса.
     */
    private final AtomicReference<Instant> lastTriggerAt = new AtomicReference<>();

    public AdminSchedulerService(
            AdminSchedulerRepository repository,
            SchedulerHealthProvider healthProvider,
            NotificationSchedulerService schedulerService,
            CareScheduleRepository careScheduleRepository,
            AdminProperties props
    ) {
        this.repository = repository;
        this.healthProvider = healthProvider;
        this.schedulerService = schedulerService;
        this.careScheduleRepository = careScheduleRepository;
        this.adminZone = resolveAdminZone(props);
    }

    private static ZoneId resolveAdminZone(AdminProperties props) {
        String configured = props.getDashboard() == null ? null : props.getDashboard().getTimezone();
        if (configured == null || configured.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(configured);
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    /** Public для шаблона/контроллера, если потребуется показать «(в зоне X)». */
    public ZoneId getAdminZone() {
        return adminZone;
    }

    public SchedulerPageDto buildPage() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusHours(24);

        SchedulerHealth health = healthProvider.currentHealth();
        long inQueue = repository.countSchedulesInQueue(now);
        long sent24h = repository.countSentSince(since);

        // Часы группируем и показываем в админской TZ — на UTC-сервере
        // это даёт админу понятный таймлайн «по моим часам», а не сдвинутый на N часов.
        Map<Integer, Long> byHour = repository.sentByHourSince(since, adminZone.getId());
        int currentHourAdmin = Instant.now().atZone(adminZone).getHour();

        List<SchedulerPageDto.HourBucket> buckets = new ArrayList<>(24);
        for (int[] row : repository.hourlyTimeline(currentHourAdmin, byHour)) {
            String label = String.format("%02d:00", row[0]);
            buckets.add(new SchedulerPageDto.HourBucket(label, row[1]));
        }

        List<CareSchedule> upcoming = careScheduleRepository
                .findSchedulesDueBefore(now.plusHours(QUEUE_HORIZON_HOURS));
        boolean truncated = upcoming.size() > QUEUE_LIMIT;
        if (truncated) {
            upcoming = upcoming.subList(0, QUEUE_LIMIT);
        }
        List<QueuedNotificationDto> queue = new ArrayList<>(upcoming.size());
        for (CareSchedule s : upcoming) {
            queue.add(toQueueDto(s, now));
        }

        return new SchedulerPageDto(
                health,
                inQueue,
                sent24h,
                /* errorsLast24h = */ -1,  // placeholder; реальный счёт в Phase 2
                buckets,
                cooldownSecondsLeft(),
                queue,
                truncated
        );
    }

    private QueuedNotificationDto toQueueDto(CareSchedule s, LocalDateTime now) {
        var plant = s.getPlant();
        var user = plant.getUser();
        boolean overdue = !s.getNextDueAt().isAfter(now);
        return new QueuedNotificationDto(
                s.getId(),
                user.getTelegramChatId(),
                user.getUsername(),
                plant.getName(),
                s.getTaskType().name(),
                emojiFor(s.getTaskType()),
                humanizeDue(s.getNextDueAt(), now),
                overdue,
                user.isPaused(),
                s.getIntervalDays()
        );
    }

    private static String emojiFor(com.plantcare.core.domain.enums.TaskType t) {
        return switch (t) {
            case WATERING    -> "💧";
            case MISTING     -> "💨";
            case FERTILIZING -> "🌿";
            case SOIL_CHECK  -> "🪴";
        };
    }

    /**
     * Человеко-читаемое расстояние до/от срока. «через N мин», «5 ч назад», «вчера»,
     * «23.05 14:30» для дат > 7 дней (для админ-просмотра — компактно).
     */
    private String humanizeDue(LocalDateTime due, LocalDateTime now) {
        Duration d = Duration.between(now, due);
        long minutes = d.toMinutes();
        if (minutes <= -60 * 24) {
            return formatInAdminZone(due);
        }
        if (minutes < 0) {
            long ago = -minutes;
            if (ago < 60)   return ago + " мин назад";
            return (ago / 60) + " ч назад";
        }
        if (minutes < 1)   return "сейчас";
        if (minutes < 60)  return "через " + minutes + " мин";
        long hours = minutes / 60;
        if (hours < 24)    return "через " + hours + " ч";
        return formatInAdminZone(due);
    }

    /**
     * Форматирует UTC-LocalDateTime как «DD.MM HH:mm» в админской TZ.
     * Решает баг: на UTC-сервере {@code due.format()} печатает время UTC,
     * а админ ожидает свой часовой пояс — в результате «через 2 часа» (правда)
     * рядом с «23.05 14:00» (UTC = 17:00 в RIga) выглядело противоречиво.
     */
    private String formatInAdminZone(LocalDateTime utcDateTime) {
        return utcDateTime
                .atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(adminZone)
                .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"));
    }

    /**
     * Отправить пуш по одному расписанию из админки. {@code force=true} обходит
     * фильтры (паузу, quiet-hours, дедуп) — для диагностики.
     */
    public NotificationSchedulerService.SendOneResult sendOne(Long scheduleId, boolean force) {
        log.info("Admin sendOne: scheduleId={}, force={}", scheduleId, force);
        return schedulerService.sendOneSchedule(scheduleId, force);
    }

    /** Пропустить ближайший пуш по расписанию (продвинуть next_due_at). */
    public boolean skipOne(Long scheduleId) {
        log.info("Admin skipOne: scheduleId={}", scheduleId);
        return schedulerService.skipOneSchedule(scheduleId);
    }

    /**
     * Запустить tick синхронно. Возвращает результат для шаблона.
     * Дедуп на стороне notifications_log защищает от двойных отправок при спам-кликах,
     * но дополнительно мы блокируем сам триггер на 30 секунд — это страховка от
     * пользователя, который от души зажал F5.
     */
    public TriggerResult triggerTick() {
        long cooldown = cooldownSecondsLeft();
        if (cooldown > 0) {
            log.info("Manual trigger rejected: cooldown active, {}s left", cooldown);
            return TriggerResult.cooldownActive(cooldown);
        }

        // Резервируем слот ДО запуска тика. Если поставить после — между двумя
        // нажатиями вмещаются два почти-параллельных tick'а; дедуп в notifications_log
        // в большинстве случаев их спасёт, но смысла рисковать нет.
        lastTriggerAt.set(Instant.now());

        try {
            int processed = schedulerService.triggerManually();
            log.info("Manual scheduler tick completed: processed={}", processed);
            return TriggerResult.success(processed);
        } catch (Exception e) {
            log.error("Manual scheduler tick failed: {}", e.getMessage(), e);
            // Снимаем cooldown на ошибке — повторная попытка не повредит и иногда
            // помогает отдиагностировать проблему.
            lastTriggerAt.set(null);
            return TriggerResult.failure(e.getMessage());
        }
    }

    /**
     * Сколько секунд ещё нельзя нажимать триггер.
     * 0, если можно жать прямо сейчас.
     */
    public int cooldownSecondsLeft() {
        Instant last = lastTriggerAt.get();
        if (last == null) return 0;
        long elapsed = Duration.between(last, Instant.now()).getSeconds();
        long left = TRIGGER_COOLDOWN_SECONDS - elapsed;
        return Integer.parseInt(String.valueOf(Math.max(0, left)));
    }

    /** Результат ручного триггера для шаблона. */
    public record TriggerResult(Status status, int processed, long cooldownSecondsLeft, String error) {
        public enum Status { SUCCESS, COOLDOWN, FAILURE }

        public static TriggerResult success(int processed) {
            return new TriggerResult(Status.SUCCESS, processed, 0, null);
        }

        public static TriggerResult cooldownActive(long secondsLeft) {
            return new TriggerResult(Status.COOLDOWN, 0, secondsLeft, null);
        }

        public static TriggerResult failure(String message) {
            return new TriggerResult(Status.FAILURE, 0, 0, message);
        }

        public boolean isSuccess()  { return status == Status.SUCCESS; }
        public boolean isCooldown() { return status == Status.COOLDOWN; }
        public boolean isFailure()  { return status == Status.FAILURE; }
    }
}

package com.plantcare.bot.service;

import com.plantcare.core.service.PushSender;
import com.plantcare.core.service.QuietHoursPolicy;
import com.plantcare.core.service.SchedulerHealthTracker;

import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.DigestTaskItem;
import com.plantcare.core.domain.NotificationDigest;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.UserDevice;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.metrics.MetricsService;
import com.plantcare.core.observability.SentryTags;
import com.plantcare.core.observability.SentryTags.Layer;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.NotificationDigestRepository;
import com.plantcare.core.repository.NotificationLogRepository;
import com.plantcare.core.repository.UserDeviceRepository;
import com.plantcare.bot.telegram.RateLimitedTelegramSender;
import com.plantcare.bot.telegram.SendCallbacks;
import io.micrometer.core.instrument.Timer;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.time.Clock;
import java.time.LocalDateTime;
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
    private final SchedulerHealthTracker schedulerHealthTracker;
    private final com.plantcare.core.weather.service.WeatherService weatherService;
    private final com.plantcare.core.seasonal.service.SeasonalIntervalService seasonalIntervalService;
    private final QuietHoursPolicy quietHoursPolicy;
    private final ReminderKeyboardFactory reminderKeyboardFactory;
    private final Clock clock;
    private final MetricsService metricsService;
    private final RateLimitedTelegramSender telegramSender;
    private final NotificationDeliveryCallbacks deliveryCallbacks;
    private final com.plantcare.core.service.LocationSharingService locationSharingService;
    // Issue #175: push-канал и репозиторий устройств для fan-out на мобильные клиенты
    private final PushSender pushSender;
    private final UserDeviceRepository userDeviceRepository;

    // Issue #279: lockAtMostFor > fixedRate (2m > 1m) — если инстанс повис, лок
    // освобождается через 2 мин, следующий тик уже через 1 мин возьмёт его.
    // lockAtLeastFor = 55s — гарантирует, что на быстром инстансе тик не запускается
    // дважды за одно окно (защита от <<мгновенного>> тика + рестарта).
    @Scheduled(fixedRate = 60_000)
    @SchedulerLock(name = "NotificationSchedulerService_checkAndSendNotifications",
            lockAtMostFor = "PT2M", lockAtLeastFor = "PT55S")
    @Transactional
    public void checkAndSendNotifications() {
        // Issue #114: весь тик выполняется в изолированном scope с тегом
        // layer=scheduler/feature — captureException внутри получит верные теги,
        // а вызовы weather внутри тика не загрязнят scope соседних задач
        // shared-потока @Scheduled.
        SentryTags.runWithLayer(Layer.SCHEDULER, "NotificationSchedulerService", () -> {
            // Issue #115: оборачиваем тик в Timer, чтобы в Prometheus была видна
            // длительность обработки (p50/p95/p99). Сам executeTick остаётся
            // без изменений — Timer.Sample.stop() запишет latency в hot path
            // только один раз за тик.
            Timer.Sample sample = metricsService.startSchedulerTickTimer();
            try {
                executeTick();
            } finally {
                metricsService.stopSchedulerTickTimer(sample);
            }
        });
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
            // С сезонной корректировкой (issue #67) — если выключено, вернётся базовый.
            int effective = seasonalIntervalService.effectiveIntervalDays(
                    plant, user, schedule.getIntervalDays());
            schedule.setNextDueAt(LocalDateTime.now().plusDays(effective));
            careScheduleRepository.save(schedule);
            return SendOneResult.sent();
        } catch (Exception e) {
            log.error("sendOneSchedule failed for schedule={}: {}", scheduleId, e.getMessage(), e);
            Sentry.captureException(e);
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
        // Сезонная корректировка (issue #67) — пропуск должен учитывать
        // фактический интервал текущего сезона, иначе пропуск зимой подвинет
        // меньше чем должен.
        Plant plant = schedule.getPlant();
        User user = plant.getUser();
        int effective = seasonalIntervalService.effectiveIntervalDays(
                plant, user, schedule.getIntervalDays());
        schedule.setNextDueAt(LocalDateTime.now().plusDays(effective));
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
                Sentry.captureException(e);
            }
        }

        for (CareSchedule soilCheck : standaloneSoilChecks) {
            try {
                sendNotification(soilCheck.getPlant().getUser(), soilCheck.getPlant(), soilCheck);
            } catch (Exception e) {
                log.error("Error sending soil-check notification: {}", e.getMessage(), e);
                Sentry.captureException(e);
            }
        }

        for (CareSchedule acclim : standaloneAcclimWaterings) {
            try {
                sendNotification(acclim.getPlant().getUser(), acclim.getPlant(), acclim);
            } catch (Exception e) {
                log.error("Error sending acclimation watering notification: {}", e.getMessage(), e);
                Sentry.captureException(e);
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
                Sentry.captureException(e);
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

        // Issue #70: глобальная пауза пользователя имеет приоритет над паузой локации.
        // Если локация на паузе — пропускаем уведомление по этому растению.
        if (plant.getLocation() != null && plant.getLocation().isPaused()) {
            return false;
        }

        // Quiet-hours считаем по абсолютному Instant из clock'а, а не из
        // LocalDateTime-параметра `now` — иначе при не-UTC JVM TZ wall-clock
        // в `now` интерпретировался бы как UTC и quiet-hours съезжали бы на
        // offset (см. issue #118, регрессия после удаления приватного isQuietHours).
        if (quietHoursPolicy.isQuiet(user, clock.instant())) {
            return false;
        }

        LocalDateTime deduplicationCutoff = now.minusHours(DEDUP_HOURS);

        return !notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(
                plant.getId(),
                schedule.getTaskType(),
                deduplicationCutoff
        );
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

        // Погодная подсказка для полива (issue #69). Добавляется одной
        // строкой, только если у юзера погода настроена И задача — полив.
        // Если Open-Meteo молчит или кеш пустой — push уходит без хинта.
        text = appendWeatherHintIfWatering(text, user, schedule);

        final long plantId = plant.getId();
        final TaskType taskType = schedule.getTaskType();

        // Telegram-канал: только если у юзера привязан Telegram-чат (issue #88:
        // mobile-only юзеры могут не иметь telegramChatId).
        if (user.getTelegramChatId() != null) {
            SendMessage message = SendMessage.builder()
                    .chatId(user.getTelegramChatId().toString())
                    .text(text)
                    .replyMarkup(keyboard)
                    .build();

            // Issue #29: отправка ушла в rate-limited очередь. Bookkeeping (дедуп-лог +
            // метрики, пометка blocked на 403) выполняется в колбэках на воркер-потоке
            // очереди вне этой транзакции. Захватываем только примитивные id, чтобы не
            // тащить detached-entity между потоками. Дедуп-лог пишется по факту успешной
            // отправки — как и в синхронной версии (очередь дренирует задолго до
            // следующего 60с-тика, окно дедупа не нарушается).
            final long chatId = user.getTelegramChatId();
            telegramSender.enqueue(message, new SendCallbacks(
                    () -> {
                        deliveryCallbacks.onSent(plantId, taskType);
                        log.info("Sent notification for plant id={} to chat {} (acclimation={})",
                                plantId, chatId, inAcclimation);
                    },
                    e -> deliveryCallbacks.onFailed(chatId, e)));
        }

        // Issue #175: fan-out на мобильные устройства пользователя.
        // Telegram-юзеры тоже могут иметь mobile-устройства (оба канала получат уведомление).
        // Тихие часы / paused_until уже проверены в shouldSend() выше.
        fanOutToMobileDevices(user, plant, text);

        // Совместный уход (issue #77): тот же push веером уходит caretaker'ам с
        // доступом к локации растения. Клавиатура завязана на scheduleId, а callback
        // «Полил» не скоупится по юзеру — значит, кто угодно из них может закрыть
        // задачу и она закроется у всех. Bookkeeping/дедуп ведём только по
        // основной отправке владельцу (выше), чтобы не задвоить метрики.
        fanOutToCaretakers(plant, text, keyboard);
    }

    /**
     * Fan-out push-уведомления на все зарегистрированные мобильные устройства
     * пользователя (issue #175). Quiet hours и paused_until уже проверены выше.
     * Ошибки отдельного устройства не останавливают остальные.
     */
    private void fanOutToMobileDevices(User user, Plant plant, String text) {
        List<UserDevice> devices = userDeviceRepository.findByUserId(user.getId());
        if (devices.isEmpty()) {
            return;
        }
        String title = "Plants Care";
        for (UserDevice device : devices) {
            try {
                pushSender.send(device.getPushToken(), title, text);
                log.debug("Push sent to device id={} platform={} for plant id={}",
                        device.getId(), device.getPlatform(), plant.getId());
            } catch (Exception e) {
                log.warn("Push failed for device id={} platform={}: {}",
                        device.getId(), device.getPlatform(), e.getMessage());
                Sentry.captureException(e);
            }
        }
    }

    /**
     * Веерная рассылка готового push'а всем caretaker'ам локации растения
     * (issue #77). Никакого нового bookkeeping — это копия уведомления владельца.
     */
    private void fanOutToCaretakers(Plant plant, String text, InlineKeyboardMarkup keyboard) {
        Long locationId = plant.getLocation() != null ? plant.getLocation().getId() : null;
        if (locationId == null) {
            return;
        }

        List<Long> caretakerChatIds = locationSharingService.caretakerChatIdsForLocation(locationId);
        for (Long caretakerChatId : caretakerChatIds) {
            SendMessage copy = SendMessage.builder()
                    .chatId(caretakerChatId.toString())
                    .text(text)
                    .replyMarkup(keyboard)
                    .build();
            final long targetChatId = caretakerChatId;
            telegramSender.enqueue(copy, new SendCallbacks(
                    () -> log.info("Fanned out notification for plant id={} to caretaker chat {}",
                            plant.getId(), targetChatId),
                    e -> log.warn("Failed to fan out notification to caretaker chat {}: {}",
                            targetChatId, e.getMessage())));
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

        // Issue #29: см. комментарий в sendNotification. Каждый сгруппированный пуш
        // считаем за отправленное уведомление по каждой задаче (срезы по task_type) +
        // отдельный счётчик дайджестов. Bookkeeping — в колбэке на воркер-потоке.
        final List<NotificationDeliveryCallbacks.DigestLogItem> logItems = schedules.stream()
                .map(s -> new NotificationDeliveryCallbacks.DigestLogItem(
                        s.getPlant().getId(), s.getTaskType()))
                .toList();
        final long chatId = user.getTelegramChatId();
        final long digestId = savedDigest.getId();
        final int taskCount = schedules.size();
        telegramSender.enqueue(message, new SendCallbacks(
                () -> {
                    deliveryCallbacks.onDigestSent(logItems);
                    log.info("Sent digest id={} with {} tasks to chat {}",
                            digestId, taskCount, chatId);
                },
                e -> deliveryCallbacks.onFailed(chatId, e)));

        // Совместный уход (issue #77): дайджест веером уходит caretaker'ам всех
        // локаций, чьи растения попали в дайджест. Клавиатура завязана на digestId
        // и закрывает задачи у всех участников. Дедуп по chatId — один caretaker
        // может иметь доступ к нескольким локациям в одном дайджесте.
        String digestText = buildDigestText(items);
        InlineKeyboardMarkup digestKeyboard = buildDigestKeyboard(digestId);
        java.util.Set<Long> caretakerChatIds = new java.util.LinkedHashSet<>();
        schedules.stream()
                .map(s -> s.getPlant().getLocation() != null ? s.getPlant().getLocation().getId() : null)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .forEach(locationId ->
                        caretakerChatIds.addAll(locationSharingService.caretakerChatIdsForLocation(locationId)));

        for (Long caretakerChatId : caretakerChatIds) {
            SendMessage copy = SendMessage.builder()
                    .chatId(caretakerChatId.toString())
                    .text(digestText)
                    .replyMarkup(digestKeyboard)
                    .build();
            final long targetChatId = caretakerChatId;
            telegramSender.enqueue(copy, new SendCallbacks(
                    () -> log.info("Fanned out digest id={} to caretaker chat {}", digestId, targetChatId),
                    e -> log.warn("Failed to fan out digest to caretaker chat {}: {}",
                            targetChatId, e.getMessage())));
        }
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
        return reminderKeyboardFactory.buildReminderKeyboard(scheduleId, taskType);
    }

    private String taskLabel(TaskType taskType) {
        return switch (taskType) {
            case WATERING -> "полить";
            case MISTING -> "опрыскать";
            case FERTILIZING -> "удобрить";
            case SOIL_CHECK -> "проверить грунт";
        };
    }

    /**
     * Добавляет к тексту push'а одну строку про текущую влажность — если
     * у юзера погода настроена и задача — полив. Любой отказ Open-Meteo
     * или невалидное состояние → текст возвращается как был (issue #69 AC:
     * «если сервис недоступен — бот продолжает работать без погодной подсказки»).
     */
    private String appendWeatherHintIfWatering(
            String text,
            com.plantcare.core.domain.User user,
            com.plantcare.core.domain.CareSchedule schedule
    ) {
        if (schedule.getTaskType() != com.plantcare.core.domain.enums.TaskType.WATERING) {
            return text;
        }
        if (!user.isWeatherUsable()) {
            return text;
        }
        return weatherService.getCurrentHumidity(user)
                .map(info -> text + "\n\n" + info.renderLine())
                .orElse(text);
    }
}
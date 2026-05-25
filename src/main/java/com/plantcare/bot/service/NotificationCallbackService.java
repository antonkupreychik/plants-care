package com.plantcare.bot.service;

import com.plantcare.core.service.PlantAcclimationService;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.QuietHoursPolicy;
import com.plantcare.core.service.UserService;

import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.metrics.MetricsService;
import com.plantcare.core.metrics.MetricsService.CallbackOutcome;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.util.TimezoneSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Обрабатывает callback'и от inline-кнопок уведомлений.
 * Формат callback_data: v1:{action}:{idOrPayload}
 * Действия:
 *   - done / snooze / skip                — на расписании (scheduleId). snooze открывает
 *                                            клавиатуру выбора интервала, не сдвигает сразу
 *   - snooze_pick / snooze_back           — выбор интервала snooze: час/вечером/завтра
 *                                            с учётом quiet-hours (issue #118)
 *   - bulk_done                           — массовый полив локации (locationId, issue #19)
 *   - wabund / wsoil                      — расширенный полив (issue #71)
 *   - soil_dry / soil_wet / soil_unk / soil_water — SOIL_CHECK (issue #74)
 *   - accl_soil / accl_snooze / accl_checkin     — акклиматизация (issue #75)
 *   - back_care / back_pick / back_custom — ретро-отметка ухода, делегируется в
 *                                            {@link BackdatedCareCallbackService} (issue #118)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationCallbackService {

    private static final int DEDUP_SECONDS = 60;
    private static final int GRACE_PERIOD_HOURS = 24;

    /**
     * Граница «уже вечер» для snooze:evening — если в локали юзера сейчас ≥ 18:30,
     * «вечером» означает 19:00 следующего дня.
     */
    private static final LocalTime EVENING_CUTOFF = LocalTime.of(18, 30);
    /** Целевое локальное время snooze:evening. */
    private static final LocalTime EVENING_TARGET = LocalTime.of(19, 0);

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    /** Формат подтверждения snooze: «28.05 19:00». */
    private static final DateTimeFormatter SNOOZE_CONFIRM_FMT =
            DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final CareScheduleRepository careScheduleRepository;
    private final CareHistoryRepository careHistoryRepository;
    private final PlantService plantService;
    private final UserService userService;
    private final PlantCardService plantCardService;
    private final PlantAcclimationService plantAcclimationService;
    private final com.plantcare.core.seasonal.service.SeasonalIntervalService seasonalIntervalService;
    private final QuietHoursPolicy quietHoursPolicy;
    private final ReminderKeyboardFactory reminderKeyboardFactory;
    private final BackdatedCareCallbackService backdatedCareCallbackService;
    private final Clock clock;
    private final MetricsService metricsService;

    @Transactional
    public void handleCallback(CallbackQuery callbackQuery, TelegramClient client) {
        String data = callbackQuery.getData();
        String callbackId = callbackQuery.getId();
        Long chatId = callbackQuery.getMessage().getChatId();
        Integer messageId = callbackQuery.getMessage().getMessageId();

        String[] parts = data.split(":");

        if (parts.length < 3) {
            answerCallback(client, callbackId, "❌ Неизвестная команда");
            metricsService.recordCallback("unknown", CallbackOutcome.ERROR);
            return;
        }

        String action = parts[1];

        // Массовый полив локации (issue #19) — payload это locationId, а не scheduleId,
        // и логика своя, поэтому веткаем рано.
        if ("bulk_done".equals(action)) {
            handleBulkDone(parts[2], chatId, callbackId, client);
            return;
        }

        // Расширенный полив (issue #71): двухшаговый flow «Обильно?» → «Сухая?».
        // Состояние между шагами хранится в самом callback_data, чтобы flow был
        // устойчив к перерывам (юзер открывает другие чаты, перезапускает бота и т.п.).
        //   v1:wabund:<scheduleId>:<HEAVY|NORMAL>            (шаг 2 после «Полил»)
        //   v1:wsoil:<scheduleId>:<abundance>:<DRY|WET|UNKNOWN>  (финальный)
        if ("wabund".equals(action)) {
            handleWateringAbundance(parts, chatId, messageId, callbackId, client);
            return;
        }
        if ("wsoil".equals(action)) {
            handleWateringSoilDry(parts, chatId, messageId, callbackId, client);
            return;
        }

        // Акклиматизация (issue #75):
        //   v1:accl_soil:<scheduleId>:<DRY|WET|UNKNOWN>   — ответ на «проверь грунт»
        //   v1:accl_snooze:<scheduleId>:<days>            — «Напомнить через N дней»
        //   v1:accl_checkin:<plantId>:<OK|WILT|YELLOW>    — ответ на «как растение?»
        if ("accl_soil".equals(action)) {
            handleAcclimationSoilAnswer(parts, chatId, messageId, callbackId, client);
            return;
        }
        if ("accl_snooze".equals(action)) {
            handleAcclimationSnooze(parts, chatId, messageId, callbackId, client);
            return;
        }
        if ("accl_checkin".equals(action)) {
            handleAcclimationCheckin(parts, chatId, messageId, callbackId, client);
            return;
        }

        // SOIL_CHECK actions (issue #74) — payload это scheduleId SOIL_CHECK расписания,
        // либо WATERING scheduleId для soil_water (CTA "Полить сейчас" после DRY).
        switch (action) {
            case "soil_dry" -> { handleSoilCheckResult(parts[2], "DRY",     chatId, messageId, callbackId, client); return; }
            case "soil_wet" -> { handleSoilCheckResult(parts[2], "WET",     chatId, messageId, callbackId, client); return; }
            case "soil_unk" -> { handleSoilCheckResult(parts[2], "UNKNOWN", chatId, messageId, callbackId, client); return; }
            case "soil_water" -> { handleSoilCheckWaterNow(parts[2], chatId, messageId, callbackId, client); return; }
            default -> { /* fallthrough — обычный done/snooze/skip */ }
        }

        // Snooze-flow с выбором интервала (issue #118).
        //   v1:snooze_pick:<scheduleId>:<hour|evening|tomorrow>
        //   v1:snooze_back:<scheduleId>
        if ("snooze_pick".equals(action)) {
            handleSnoozePick(parts, chatId, messageId, callbackId, client);
            return;
        }
        if ("snooze_back".equals(action)) {
            handleSnoozeBack(parts, chatId, messageId, callbackId, client);
            return;
        }

        // Ретро-отметка ухода (issue #118) — делегируем в отдельный сервис,
        // чтобы не разрастать этот класс. Все три callback'а имеют префикс back_*
        // (a) v1:back_care:<plantId>
        // (b) v1:back_pick:<plantId>:<taskType>:<daysAgo>
        // (c) v1:back_custom:<plantId>:<taskType>
        if ("back_care".equals(action) || "back_pick".equals(action) || "back_custom".equals(action)) {
            backdatedCareCallbackService.handleCallback(action, parts, chatId, messageId, callbackId, client);
            return;
        }

        Long scheduleId;
        try {
            scheduleId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            answerCallback(client, callbackId, "❌ Неверный ID");
            metricsService.recordCallback(action, CallbackOutcome.ERROR);
            return;
        }

        Optional<CareSchedule> optSchedule = careScheduleRepository.findById(scheduleId);

        if (optSchedule.isEmpty()) {
            answerCallback(client, callbackId, "❌ Расписание не найдено");
            metricsService.recordCallback(action, CallbackOutcome.ERROR);
            return;
        }

        CareSchedule schedule = optSchedule.get();
        Plant plant = schedule.getPlant();

        if (plant.isArchived()) {
            editMessage(client, chatId, messageId, "Растение уже удалено");
            answerCallback(client, callbackId, "Растение удалено");
            // Архив — это идемпотентный «уже не актуально», а не ошибка.
            metricsService.recordCallback(action, CallbackOutcome.IDEMPOTENT);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        String responseText;
        String alertText;

        switch (action) {
            case "done" -> {
                // issue #71: для WATERING начинаем двухшаговый flow с уточнениями
                // (обильность + сухость грунта). MISTING/FERTILIZING — как раньше.
                if (schedule.getTaskType() == TaskType.WATERING) {
                    askWateringAbundance(plant.getName(), scheduleId, chatId, messageId, client);
                    answerCallback(client, callbackId, "");
                    // OK — flow корректно отстартовал. Финальная отметка
                    // (с wabund/wsoil) считается отдельным callback'ом.
                    metricsService.recordCallback(action, CallbackOutcome.OK);
                    return;
                }

                boolean marked = markScheduleDone(schedule, now);

                if (!marked) {
                    answerCallback(client, callbackId, "Уже отмечено!");
                    metricsService.recordCallback(action, CallbackOutcome.IDEMPOTENT);
                    return;
                }

                String timeStr = now.format(TIME_FMT);
                String nextDateStr = schedule.getNextDueAt().format(DATE_FMT);
                String doneVerb = doneVerb(schedule.getTaskType());
                String nextLabel = nextLabel(schedule.getTaskType());

                responseText = "✅ " + doneVerb + " " + plant.getName() + " в " + timeStr + ".\n"
                        + nextLabel + " — " + nextDateStr;
                alertText = "Отмечено!";
            }
            case "snooze" -> {
                // issue #118: snooze без выбора (старый формат v1:snooze:<id> +
                // digest expand-keyboard) теперь открывает клавиатуру выбора
                // интервала вместо немедленного сдвига на 2 часа. Сам сдвиг
                // делает handleSnoozePick после выбора варианта.
                showSnoozeChoice(plant.getName(), scheduleId, chatId, messageId, client);
                answerCallback(client, callbackId, "");
                metricsService.recordCallback(action, CallbackOutcome.OK);
                return;
            }
            case "skip" -> {
                if (isDuplicateDone(plant, schedule, now)) {
                    answerCallback(client, callbackId, "Уже отмечено!");
                    metricsService.recordCallback(action, CallbackOutcome.IDEMPOTENT);
                    return;
                }

                CareHistory history = CareHistory.builder()
                        .plant(plant)
                        .taskType(schedule.getTaskType())
                        .doneAt(now)
                        .onTime(false)
                        .note("skipped")
                        .build();

                careHistoryRepository.save(history);

                // Пересчитываем next_due_at от now()
                schedule.rescheduleFrom(now);
                careScheduleRepository.save(schedule);

                String nextDateStr = schedule.getNextDueAt().format(DATE_FMT);

                responseText = "❌ " + plant.getName() + " — пропущено.\n"
                        + "Следующий раз: " + nextDateStr;
                alertText = "Пропущено!";
                log.info("Schedule {} skipped, next due at {}", scheduleId, schedule.getNextDueAt());
            }
            default -> {
                answerCallback(client, callbackId, "❌ Неизвестное действие");
                metricsService.recordCallback(action, CallbackOutcome.ERROR);
                return;
            }
        }

        editMessage(client, chatId, messageId, responseText);
        answerCallback(client, callbackId, alertText);
        metricsService.recordCallback(action, CallbackOutcome.OK);
    }

    // ====================================================================
    // Расширенный полив (issue #71): «Обильно?» → «Сухая?» → запись истории.
    // ====================================================================

    /**
     * Стартует двухшаговый flow «Полив с деталями» — вызывается из карточки
     * растения (PLANT:CARE:&lt;id&gt;:WATERING в MenuCallbackService).
     *
     * @param scheduleId  id активного WATERING-расписания для растения
     * @param plantName   имя растения для текста сообщения
     * @param chatId      куда отправить
     * @param client      Telegram client
     */
    public void startWateringDetailsFlow(
            Long scheduleId, String plantName, Long chatId, TelegramClient client
    ) {
        SendMessage prompt = SendMessage.builder()
                .chatId(chatId.toString())
                .text(buildAbundanceQuestion(plantName))
                .replyMarkup(buildAbundanceKeyboard(scheduleId))
                .build();
        try {
            client.execute(prompt);
        } catch (TelegramApiException e) {
            log.error("Failed to send watering abundance question: {}", e.getMessage(), e);
        }
    }

    /**
     * Та же логика, но edit'ит существующее сообщение — используется из notification
     * callback'а (юзер уже видит пуш «Пора полить», его и переписываем).
     */
    private void askWateringAbundance(
            String plantName, Long scheduleId, Long chatId, Integer messageId, TelegramClient client
    ) {
        EditMessageText edit = EditMessageText.builder()
                .chatId(chatId.toString())
                .messageId(messageId)
                .text(buildAbundanceQuestion(plantName))
                .replyMarkup(buildAbundanceKeyboard(scheduleId))
                .build();
        try {
            client.execute(edit);
        } catch (TelegramApiException e) {
            log.error("Failed to edit message to abundance question: {}", e.getMessage(), e);
        }
    }

    private String buildAbundanceQuestion(String plantName) {
        return "💧 " + plantName + " — как полил?";
    }

    private InlineKeyboardMarkup buildAbundanceKeyboard(Long scheduleId) {
        InlineKeyboardButton heavy = InlineKeyboardButton.builder()
                .text("💦 Обильно")
                .callbackData("v1:wabund:" + scheduleId + ":HEAVY")
                .build();
        InlineKeyboardButton normal = InlineKeyboardButton.builder()
                .text("💧 Обычно")
                .callbackData("v1:wabund:" + scheduleId + ":NORMAL")
                .build();
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(heavy, normal))
                .build();
    }

    /**
     * Шаг 2: получили ответ на «Обильно?», спрашиваем про сухость грунта.
     * callback: v1:wabund:&lt;scheduleId&gt;:&lt;HEAVY|NORMAL&gt;
     */
    private void handleWateringAbundance(
            String[] parts, Long chatId, Integer messageId, String callbackId, TelegramClient client
    ) {
        if (parts.length != 4) {
            answerCallback(client, callbackId, "❌ Неверный формат");
            metricsService.recordCallback("wabund", CallbackOutcome.ERROR);
            return;
        }
        Long scheduleId;
        try {
            scheduleId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            answerCallback(client, callbackId, "❌ Неверный ID");
            metricsService.recordCallback("wabund", CallbackOutcome.ERROR);
            return;
        }
        String abundance = parts[3];
        if (!"HEAVY".equals(abundance) && !"NORMAL".equals(abundance)) {
            answerCallback(client, callbackId, "❌ Неверный ответ");
            metricsService.recordCallback("wabund", CallbackOutcome.ERROR);
            return;
        }

        Optional<CareSchedule> opt = careScheduleRepository.findById(scheduleId);
        if (opt.isEmpty() || opt.get().getTaskType() != TaskType.WATERING) {
            answerCallback(client, callbackId, "❌ Расписание не найдено");
            metricsService.recordCallback("wabund", CallbackOutcome.ERROR);
            return;
        }
        Plant plant = opt.get().getPlant();
        if (plant.isArchived()) {
            editMessage(client, chatId, messageId, "Растение уже удалено");
            answerCallback(client, callbackId, "Растение удалено");
            metricsService.recordCallback("wabund", CallbackOutcome.IDEMPOTENT);
            return;
        }

        // Шаг 2 — спрашиваем про сухость грунта. Состояние (abundance) зашиваем
        // в callback_data следующего шага, чтобы не плодить state в БД.
        EditMessageText edit = EditMessageText.builder()
                .chatId(chatId.toString())
                .messageId(messageId)
                .text("🌱 " + plant.getName() + " — земля была сухая?")
                .replyMarkup(buildSoilDryKeyboard(scheduleId, abundance))
                .build();
        try {
            client.execute(edit);
        } catch (TelegramApiException e) {
            log.error("Failed to edit message to soil-dry question: {}", e.getMessage(), e);
        }
        answerCallback(client, callbackId, "");
        metricsService.recordCallback("wabund", CallbackOutcome.OK);
    }

    private InlineKeyboardMarkup buildSoilDryKeyboard(Long scheduleId, String abundance) {
        InlineKeyboardButton dry = InlineKeyboardButton.builder()
                .text("✅ Сухая")
                .callbackData("v1:wsoil:" + scheduleId + ":" + abundance + ":DRY")
                .build();
        InlineKeyboardButton wet = InlineKeyboardButton.builder()
                .text("❌ Влажная")
                .callbackData("v1:wsoil:" + scheduleId + ":" + abundance + ":WET")
                .build();
        InlineKeyboardButton unk = InlineKeyboardButton.builder()
                .text("🤷 Не знаю")
                .callbackData("v1:wsoil:" + scheduleId + ":" + abundance + ":UNKNOWN")
                .build();
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(dry, wet, unk))
                .build();
    }

    /**
     * Шаг 3 (финал): пишем CareHistory с обильностью и сухостью, сдвигаем schedule.
     * callback: v1:wsoil:&lt;scheduleId&gt;:&lt;abundance&gt;:&lt;DRY|WET|UNKNOWN&gt;
     */
    private void handleWateringSoilDry(
            String[] parts, Long chatId, Integer messageId, String callbackId, TelegramClient client
    ) {
        if (parts.length != 5) {
            answerCallback(client, callbackId, "❌ Неверный формат");
            metricsService.recordCallback("wsoil", CallbackOutcome.ERROR);
            return;
        }
        Long scheduleId;
        try {
            scheduleId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            answerCallback(client, callbackId, "❌ Неверный ID");
            metricsService.recordCallback("wsoil", CallbackOutcome.ERROR);
            return;
        }
        String abundanceStr = parts[3];
        String soilStr = parts[4];

        boolean wasAbundant = "HEAVY".equals(abundanceStr);
        if (!"HEAVY".equals(abundanceStr) && !"NORMAL".equals(abundanceStr)) {
            answerCallback(client, callbackId, "❌ Неверный ответ");
            metricsService.recordCallback("wsoil", CallbackOutcome.ERROR);
            return;
        }
        Boolean soilWasDry = switch (soilStr) {
            case "DRY" -> Boolean.TRUE;
            case "WET" -> Boolean.FALSE;
            case "UNKNOWN" -> null;
            default -> { answerCallback(client, callbackId, "❌ Неверный ответ"); yield null; }
        };
        // Если был неизвестный soilStr — уже ответили выше, но нам нужно явно выйти.
        if (!"DRY".equals(soilStr) && !"WET".equals(soilStr) && !"UNKNOWN".equals(soilStr)) {
            metricsService.recordCallback("wsoil", CallbackOutcome.ERROR);
            return;
        }

        Optional<CareSchedule> opt = careScheduleRepository.findById(scheduleId);
        if (opt.isEmpty() || opt.get().getTaskType() != TaskType.WATERING) {
            answerCallback(client, callbackId, "❌ Расписание не найдено");
            metricsService.recordCallback("wsoil", CallbackOutcome.ERROR);
            return;
        }
        CareSchedule schedule = opt.get();
        Plant plant = schedule.getPlant();
        if (plant.isArchived()) {
            editMessage(client, chatId, messageId, "Растение уже удалено");
            answerCallback(client, callbackId, "Растение удалено");
            metricsService.recordCallback("wsoil", CallbackOutcome.IDEMPOTENT);
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        if (isDuplicateDone(plant, schedule, now)) {
            answerCallback(client, callbackId, "Уже отмечено!");
            metricsService.recordCallback("wsoil", CallbackOutcome.IDEMPOTENT);
            return;
        }

        boolean wasOnTime = isOnTime(schedule.getNextDueAt(), now);

        CareHistory history = CareHistory.builder()
                .plant(plant)
                .taskType(TaskType.WATERING)
                .doneAt(now)
                .onTime(wasOnTime)
                .wasAbundant(wasAbundant)
                .soilWasDry(soilWasDry)
                .build();
        careHistoryRepository.save(history);

        int effective = seasonalIntervalService.effectiveIntervalDays(
                plant, plant.getUser(), schedule.getIntervalDays());
        schedule.rescheduleFrom(now, effective);
        careScheduleRepository.save(schedule);

        String nextDateStr = schedule.getNextDueAt().format(DATE_FMT);
        String summary = "✅ Полил " + plant.getName() + ":\n"
                + "• " + (wasAbundant ? "обильно" : "обычно") + "\n"
                + "• земля: " + soilHumanLabel(soilStr) + "\n"
                + "Следующий полив — " + nextDateStr;
        editMessage(client, chatId, messageId, summary);

        // Карточка растения новым сообщением вниз чата (messageId=null) — чтобы
        // после отметки полива юзер сразу видел актуальное состояние растения и
        // мог продолжить уход (фото, заметки, другие задачи) без скролла.
        plantCardService.showPlantCard(
                plant.getUser(), plant.getId(), null, PlantCardService.BACK_TO_LIST, client
        );

        answerCallback(client, callbackId, "Записано");
        metricsService.recordCallback("wsoil", CallbackOutcome.OK);

        log.info("Watering with details: plant={}, abundant={}, soil_dry={}, schedule={}",
                plant.getId(), wasAbundant, soilWasDry, scheduleId);
    }

    private String soilHumanLabel(String soilStr) {
        return switch (soilStr) {
            case "DRY" -> "сухая";
            case "WET" -> "влажная";
            default -> "неизвестно";
        };
    }

    @Transactional
    public boolean markScheduleDone(CareSchedule schedule, LocalDateTime now) {
        Plant plant = schedule.getPlant();

        if (isDuplicateDone(plant, schedule, now)) {
            return false;
        }

        boolean wasOnTime = isOnTime(schedule.getNextDueAt(), now);

        CareHistory history = CareHistory.builder()
                .plant(plant)
                .taskType(schedule.getTaskType())
                .doneAt(now)
                .onTime(wasOnTime)
                .build();

        careHistoryRepository.save(history);

        // Сезонная корректировка интервала (issue #67): если выключено или
        // не применимо к этому растению — вернётся базовый интервал.
        int effective = seasonalIntervalService.effectiveIntervalDays(
                plant, plant.getUser(), schedule.getIntervalDays());
        schedule.rescheduleFrom(now, effective);
        careScheduleRepository.save(schedule);

        return true;
    }

    private boolean isDuplicateDone(Plant plant, CareSchedule schedule, LocalDateTime now) {
        Optional<CareHistory> lastEntry = careHistoryRepository
                .findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(plant.getId(), schedule.getTaskType());

        if (lastEntry.isEmpty()) {
            return false;
        }

        LocalDateTime lastDoneAt = lastEntry.get().getDoneAt();
        return lastDoneAt.plusSeconds(DEDUP_SECONDS).isAfter(now);
    }

    private boolean isOnTime(LocalDateTime scheduledAt, LocalDateTime now) {
        return !now.isAfter(scheduledAt.plusHours(GRACE_PERIOD_HOURS));
    }

    private String doneVerb(TaskType taskType) {
        return switch (taskType) {
            case WATERING -> "Полил";
            case MISTING -> "Опрыскал";
            case FERTILIZING -> "Удобрил";
            case SOIL_CHECK -> "Проверил"; // unreachable, exhaustive switch
        };
    }

    private String nextLabel(TaskType taskType) {
        return switch (taskType) {
            case WATERING -> "Следующий полив";
            case MISTING -> "Следующее опрыскивание";
            case FERTILIZING -> "Следующее удобрение";
            case SOIL_CHECK -> "Следующая проверка грунта"; // unreachable, exhaustive switch
        };
    }

    private void editMessage(TelegramClient client, Long chatId, Integer messageId, String text) {
        EditMessageText edit = EditMessageText.builder()
                .chatId(chatId.toString())
                .messageId(messageId)
                .text(text)
                .replyMarkup(null)
                .build();

        try {
            client.execute(edit);
        } catch (TelegramApiException e) {
            log.error("Failed to edit message: {}", e.getMessage(), e);
        }
    }

    /**
     * Конвертация UTC-{@code LocalDateTime} в {@code LocalDate} в TZ юзера.
     * Делегирует в {@link TimezoneSupport}.
     */
    private LocalDate inUserZone(LocalDateTime utc, User user) {
        return TimezoneSupport.dateInUserZone(utc, user);
    }

    /**
     * Обработка callback v1:bulk_done:&lt;locationId&gt; (issue #19).
     *
     * Карточку локации НЕ редактируем (по ТЗ не трогаем старые сообщения),
     * шлём отдельное итоговое сообщение в чат и алёрт коротким текстом.
     */
    private void handleBulkDone(String rawLocationId, Long chatId, String callbackId,
                                TelegramClient client) {
        Long locationId;
        try {
            locationId = Long.parseLong(rawLocationId);
        } catch (NumberFormatException e) {
            answerCallback(client, callbackId, "❌ Неверный ID локации");
            metricsService.recordCallback("bulk_done", CallbackOutcome.ERROR);
            return;
        }

        User user = userService.findByChatId(chatId).orElse(null);
        if (user == null) {
            log.warn("bulk_done callback from unknown chatId={}", chatId);
            answerCallback(client, callbackId, "❌ Сначала /start");
            metricsService.recordCallback("bulk_done", CallbackOutcome.ERROR);
            return;
        }

        PlantService.BulkCareDoneResult result =
                plantService.markBulkCareDone(user.getId(), locationId, TaskType.WATERING);

        // Растений в локации с активным WATERING нет (либо чужая локация, либо все
        // расписания отключены, либо все растения архивированы).
        if (result.total() == 0) {
            answerCallback(client, callbackId, "В этой комнате пока нечего поливать");
            metricsService.recordCallback("bulk_done", CallbackOutcome.IDEMPOTENT);
            return;
        }

        // Все растения были политы недавно (< 60 сек) — это double-click по кнопке.
        if (result.updated() == 0) {
            answerCallback(client, callbackId, "Уже полито");
            metricsService.recordCallback("bulk_done", CallbackOutcome.IDEMPOTENT);
            return;
        }

        String resultText = "Готово! 🌿 Обновил график для "
                + result.updated() + " "
                + plantsPlural(result.updated())
                + " в локации " + result.locationName();
        sendMessage(client, chatId, resultText);
        answerCallback(client, callbackId, "Готово!");
        metricsService.recordCallback("bulk_done", CallbackOutcome.OK);

        log.info("Bulk WATERING done: user={}, location={}, updated={}, deduped={}",
                user.getId(), locationId, result.updated(), result.deduped());
    }

    /**
     * Русское склонение в родительном после «для»: для 1 растения, для 2 растений,
     * для 5 растений, для 21 растения. В отличие от «день/дня/дней», здесь только
     * две формы — единственное и множественное число родительного падежа.
     */
    private String plantsPlural(int n) {
        int mod10 = n % 10;
        int mod100 = n % 100;
        return (mod10 == 1 && mod100 != 11) ? "растения" : "растений";
    }

    private void sendMessage(TelegramClient client, Long chatId, String text) {
        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .build();
        try {
            client.execute(msg);
        } catch (TelegramApiException e) {
            log.error("Failed to send bulk result message: {}", e.getMessage(), e);
        }
    }

    // ====================================================================
    // Snooze с выбором интервала (issue #118)
    // ====================================================================

    /**
     * Шаг 1: показать клавиатуру выбора интервала вместо немедленного сдвига.
     * Вызывается при клике на «⏰ Отложить» (старый callback v1:snooze:&lt;id&gt;)
     * — мы оставили это же callback_data, чтобы старые сообщения в чате тоже
     * открывали новый flow, а не падали в Unknown action.
     */
    private void showSnoozeChoice(
            String plantName, Long scheduleId, Long chatId, Integer messageId, TelegramClient client
    ) {
        EditMessageText edit = EditMessageText.builder()
                .chatId(chatId.toString())
                .messageId(messageId)
                .text("⏰ " + plantName + " — когда напомнить?")
                .replyMarkup(buildSnoozeChoiceKeyboard(scheduleId))
                .build();
        try {
            client.execute(edit);
        } catch (TelegramApiException e) {
            log.error("Failed to show snooze choice: {}", e.getMessage(), e);
        }
    }

    private InlineKeyboardMarkup buildSnoozeChoiceKeyboard(Long scheduleId) {
        InlineKeyboardButton hour = InlineKeyboardButton.builder()
                .text("🕐 Через час")
                .callbackData("v1:snooze_pick:" + scheduleId + ":hour")
                .build();
        InlineKeyboardButton evening = InlineKeyboardButton.builder()
                .text("🌆 Вечером")
                .callbackData("v1:snooze_pick:" + scheduleId + ":evening")
                .build();
        InlineKeyboardButton tomorrow = InlineKeyboardButton.builder()
                .text("📅 Завтра")
                .callbackData("v1:snooze_pick:" + scheduleId + ":tomorrow")
                .build();
        InlineKeyboardButton back = InlineKeyboardButton.builder()
                .text("◀️ Назад")
                .callbackData("v1:snooze_back:" + scheduleId)
                .build();
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(hour, evening, tomorrow))
                .keyboardRow(new InlineKeyboardRow(back))
                .build();
    }

    /**
     * Обработка выбора варианта snooze. Считает целевой момент в TZ юзера,
     * применяет quiet-hours и сохраняет {@code nextDueAt} как UTC-LocalDateTime
     * (как и остальной код проекта).
     *
     * <p>callback: {@code v1:snooze_pick:<scheduleId>:<hour|evening|tomorrow>}
     */
    private void handleSnoozePick(
            String[] parts, Long chatId, Integer messageId, String callbackId, TelegramClient client
    ) {
        if (parts.length != 4) {
            answerCallback(client, callbackId, "❌ Неверный формат");
            return;
        }
        Long scheduleId;
        try {
            scheduleId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            answerCallback(client, callbackId, "❌ Неверный ID");
            return;
        }
        String option = parts[3];

        Optional<CareSchedule> opt = careScheduleRepository.findById(scheduleId);
        if (opt.isEmpty()) {
            answerCallback(client, callbackId, "❌ Расписание не найдено");
            return;
        }
        CareSchedule schedule = opt.get();
        // Если юзер выключил тип ухода после того, как пуш ушёл, — findDueSchedules
        // его больше не подхватит, но snooze всё равно поставит next_due_at и юзер
        // увидит «⏰ Напомню...», которое никогда не сработает. Это silent UX failure.
        if (!schedule.isActive()) {
            editMessage(client, chatId, messageId, "Это расписание отключено");
            answerCallback(client, callbackId, "Расписание отключено");
            return;
        }
        Plant plant = schedule.getPlant();
        if (plant.isArchived()) {
            editMessage(client, chatId, messageId, "Растение уже удалено");
            answerCallback(client, callbackId, "Растение удалено");
            return;
        }

        User user = plant.getUser();
        ZoneId userZone = TimezoneSupport.zoneOf(user);
        Instant nowInstant = clock.instant();

        Instant target = switch (option) {
            case "hour"     -> nowInstant.plusSeconds(3600);
            case "tomorrow" -> nowInstant.plusSeconds(24 * 3600);
            case "evening"  -> computeEveningTarget(nowInstant, userZone);
            default         -> null;
        };
        if (target == null) {
            answerCallback(client, callbackId, "❌ Неизвестный вариант");
            return;
        }

        Instant shifted = quietHoursPolicy.shiftOutOfQuietHours(user, target);
        boolean wasShifted = !shifted.equals(target);

        LocalDateTime newNextDueUtc = LocalDateTime.ofInstant(shifted, ZoneOffset.UTC);
        schedule.setNextDueAt(newNextDueUtc);
        careScheduleRepository.save(schedule);

        String localStr = shifted.atZone(userZone).format(SNOOZE_CONFIRM_FMT);
        String confirm = "⏰ Напомню " + localStr
                + (wasShifted ? " (сдвинуто из тихих часов)" : ".");
        editMessage(client, chatId, messageId, confirm);
        answerCallback(client, callbackId, "Отложено");

        log.info("Snooze picked: schedule={} option={} target={} shifted={} wasShifted={}",
                scheduleId, option, target, shifted, wasShifted);
    }

    /**
     * Возвращает Instant, соответствующий 19:00 локального дня юзера.
     * Если сейчас уже ≥ 18:30 локально — берём 19:00 следующего дня.
     */
    private Instant computeEveningTarget(Instant nowInstant, ZoneId userZone) {
        ZonedDateTime nowLocal = nowInstant.atZone(userZone);
        LocalDate dayLocal = nowLocal.toLocalDate();
        if (!nowLocal.toLocalTime().isBefore(EVENING_CUTOFF)) {
            dayLocal = dayLocal.plusDays(1);
        }
        return ZonedDateTime.of(dayLocal, EVENING_TARGET, userZone).toInstant();
    }

    /**
     * «Назад» из choice-клавиатуры — восстанавливаем оригинальную клавиатуру
     * напоминания (Сделал/Отложить/Пропустить), текст оставляем как был.
     * Используем editMessageReplyMarkup, чтобы не пытаться угадать исходный
     * текст пуша (он у разных типов задач разный и зависит от погоды).
     *
     * <p>callback: {@code v1:snooze_back:<scheduleId>}
     */
    private void handleSnoozeBack(
            String[] parts, Long chatId, Integer messageId, String callbackId, TelegramClient client
    ) {
        if (parts.length != 3) {
            answerCallback(client, callbackId, "❌ Неверный формат");
            return;
        }
        Long scheduleId;
        try {
            scheduleId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            answerCallback(client, callbackId, "❌ Неверный ID");
            return;
        }

        Optional<CareSchedule> opt = careScheduleRepository.findById(scheduleId);
        if (opt.isEmpty()) {
            answerCallback(client, callbackId, "❌ Расписание не найдено");
            return;
        }
        CareSchedule schedule = opt.get();

        // Если расписание уже отключили — восстанавливать reminder-клавиатуру
        // бессмысленно: кнопки «Сделал/Отложить/Пропустить» будут вести в никуда.
        // Чистим кнопки и сообщаем юзеру явно.
        if (!schedule.isActive()) {
            editMessage(client, chatId, messageId, "Расписание уже отключено");
            answerCallback(client, callbackId, "Расписание отключено");
            return;
        }

        InlineKeyboardMarkup original = reminderKeyboardFactory.buildReminderKeyboard(
                scheduleId, schedule.getTaskType());

        EditMessageReplyMarkup edit = EditMessageReplyMarkup.builder()
                .chatId(chatId.toString())
                .messageId(messageId)
                .replyMarkup(original)
                .build();
        try {
            client.execute(edit);
        } catch (TelegramApiException e) {
            log.error("Failed to restore reminder keyboard: {}", e.getMessage(), e);
        }
        answerCallback(client, callbackId, "");
    }

    // ====================================================================
    // SOIL_CHECK (issue #74)
    // ====================================================================

    /**
     * Обработка ответа на проверку грунта: DRY / WET / UNKNOWN.
     *
     * 1) Записываем CareHistory(SOIL_CHECK, note="soil:&lt;result&gt;"), считаем on_time
     *    по тому же правилу 24h grace, что и для остальных типов.
     * 2) Сдвигаем next_due_at SOIL_CHECK-расписания на now + interval.
     * 3) Редактируем исходное сообщение: убираем кнопки, ставим итог.
     * 4) Для DRY дополнительно шлём отдельное сообщение с CTA "💧 Полить сейчас"
     *    — но только если у растения есть активное WATERING-расписание.
     */
    private void handleSoilCheckResult(
            String rawScheduleId,
            String result,
            Long chatId,
            Integer messageId,
            String callbackId,
            TelegramClient client
    ) {
        // Маппинг result → tag-значение action: явный switch, чтобы имя
        // метрики совпадало с callback action из роутера (см. switch выше,
        // case "soil_unk"). Конкатенация ".toLowerCase()" давала бы
        // "soil_unknown" против "soil_unk" в whitelist → расщепление
        // дашбордов на два имени и подмена на "unknown".
        String metricAction = switch (result) {
            case "DRY" -> "soil_dry";
            case "WET" -> "soil_wet";
            case "UNKNOWN" -> "soil_unk";
            default -> "unknown";
        };
        Long scheduleId;
        try {
            scheduleId = Long.parseLong(rawScheduleId);
        } catch (NumberFormatException e) {
            answerCallback(client, callbackId, "❌ Неверный ID");
            metricsService.recordCallback(metricAction, CallbackOutcome.ERROR);
            return;
        }

        Optional<CareSchedule> opt = careScheduleRepository.findById(scheduleId);
        if (opt.isEmpty()) {
            answerCallback(client, callbackId, "❌ Расписание не найдено");
            metricsService.recordCallback(metricAction, CallbackOutcome.ERROR);
            return;
        }
        CareSchedule schedule = opt.get();

        if (schedule.getTaskType() != TaskType.SOIL_CHECK) {
            answerCallback(client, callbackId, "❌ Неверный тип");
            metricsService.recordCallback(metricAction, CallbackOutcome.ERROR);
            return;
        }

        Plant plant = schedule.getPlant();
        if (plant.isArchived()) {
            editMessage(client, chatId, messageId, "🗑 Растение уже удалено.");
            answerCallback(client, callbackId, "");
            metricsService.recordCallback(metricAction, CallbackOutcome.IDEMPOTENT);
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        // Дедуп — двойной тап. Дубликат считаем по последней SOIL_CHECK-записи.
        Optional<CareHistory> last = careHistoryRepository
                .findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(plant.getId(), TaskType.SOIL_CHECK);
        if (last.isPresent() && last.get().getDoneAt().plusSeconds(DEDUP_SECONDS).isAfter(now)) {
            answerCallback(client, callbackId, "Уже отмечено!");
            metricsService.recordCallback(metricAction, CallbackOutcome.IDEMPOTENT);
            return;
        }

        boolean wasOnTime = isOnTime(schedule.getNextDueAt(), now);

        CareHistory history = CareHistory.builder()
                .plant(plant)
                .taskType(TaskType.SOIL_CHECK)
                .doneAt(now)
                .onTime(wasOnTime)
                .note("soil:" + result)
                .build();
        careHistoryRepository.save(history);

        schedule.rescheduleFrom(now);
        careScheduleRepository.save(schedule);

        // Дата в TZ юзера — иначе у юзеров в +3..+5 «следующая проверка — DD.MM»
        // может показывать на сутки раньше, если scheduler.rescheduleFrom
        // выставил время поздно вечером по UTC.
        String nextDateStr = inUserZone(schedule.getNextDueAt(), plant.getUser()).format(DATE_FMT);

        // 3) Edit оригинальное сообщение — итог проверки.
        String summary = switch (result) {
            case "DRY"     -> "✅ Грунт сухой. Следующая проверка — " + nextDateStr;
            case "WET"     -> "💧 Грунт влажный. Полив пока не нужен.\nСледующая проверка — " + nextDateStr;
            case "UNKNOWN" -> "🤷 Ок. Проверь пальцем/палочкой на 2–3 см вглубь.\n"
                              + "Следующая проверка — " + nextDateStr;
            default        -> "Грунт проверен. Следующая проверка — " + nextDateStr;
        };
        editMessage(client, chatId, messageId, summary);

        // 4) Если сухой — отдельным сообщением предлагаем полить.
        if ("DRY".equals(result)) {
            CareSchedule wateringSchedule = careScheduleRepository
                    .findByPlantIdAndTaskType(plant.getId(), TaskType.WATERING)
                    .filter(CareSchedule::isActive)
                    .orElse(null);

            if (wateringSchedule != null) {
                sendSoilDryFollowUp(chatId, plant.getName(), wateringSchedule.getId(), client);
            }
        }

        answerCallback(client, callbackId, switch (result) {
            case "DRY" -> "Записано: сухо";
            case "WET" -> "Записано: влажно";
            default    -> "Записано";
        });
        metricsService.recordCallback(metricAction, CallbackOutcome.OK);

        log.info("Soil check result for plant {} (schedule {}): {}",
                plant.getId(), scheduleId, result);
    }

    /**
     * CTA после DRY: "💧 Полить сейчас" — переиспользует обычный markScheduleDone
     * для WATERING-расписания того же растения.
     */
    private void handleSoilCheckWaterNow(
            String rawWateringScheduleId,
            Long chatId,
            Integer messageId,
            String callbackId,
            TelegramClient client
    ) {
        Long wateringScheduleId;
        try {
            wateringScheduleId = Long.parseLong(rawWateringScheduleId);
        } catch (NumberFormatException e) {
            answerCallback(client, callbackId, "❌ Неверный ID");
            metricsService.recordCallback("soil_water", CallbackOutcome.ERROR);
            return;
        }

        Optional<CareSchedule> opt = careScheduleRepository.findById(wateringScheduleId);
        if (opt.isEmpty() || opt.get().getTaskType() != TaskType.WATERING) {
            answerCallback(client, callbackId, "❌ Расписание полива не найдено");
            metricsService.recordCallback("soil_water", CallbackOutcome.ERROR);
            return;
        }
        CareSchedule wateringSchedule = opt.get();
        Plant plant = wateringSchedule.getPlant();

        if (plant.isArchived()) {
            editMessage(client, chatId, messageId, "🗑 Растение уже удалено.");
            answerCallback(client, callbackId, "");
            metricsService.recordCallback("soil_water", CallbackOutcome.IDEMPOTENT);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        boolean marked = markScheduleDone(wateringSchedule, now);

        if (!marked) {
            answerCallback(client, callbackId, "Уже отмечено!");
            metricsService.recordCallback("soil_water", CallbackOutcome.IDEMPOTENT);
            return;
        }

        String nextDateStr = inUserZone(wateringSchedule.getNextDueAt(), plant.getUser())
                .format(DATE_FMT);
        editMessage(client, chatId, messageId,
                "💧 Полил " + plant.getName() + ".\nСледующий полив — " + nextDateStr);
        answerCallback(client, callbackId, "Готово!");
        metricsService.recordCallback("soil_water", CallbackOutcome.OK);

        log.info("Soil-check follow-up watering done: plant={}, schedule={}",
                plant.getId(), wateringScheduleId);
    }

    private void sendSoilDryFollowUp(Long chatId, String plantName, Long wateringScheduleId,
                                     TelegramClient client) {
        InlineKeyboardButton waterBtn = InlineKeyboardButton.builder()
                .text("💧 Полить сейчас")
                .callbackData("v1:soil_water:" + wateringScheduleId)
                .build();

        InlineKeyboardButton snoozeBtn = InlineKeyboardButton.builder()
                .text("⏰ Отложить полив")
                .callbackData("v1:snooze:" + wateringScheduleId)
                .build();

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(waterBtn, snoozeBtn))
                .build();

        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text("Похоже, " + plantName + " пора полить.")
                .replyMarkup(keyboard)
                .build();

        try {
            client.execute(msg);
        } catch (TelegramApiException e) {
            log.error("Failed to send soil-dry follow-up: {}", e.getMessage(), e);
        }
    }

    // ====================================================================
    // Акклиматизация (issue #75)
    // ====================================================================

    /**
     * Ответ на «Проверь грунт» в acclimation-режиме (issue #75).
     *
     * <p>callback: {@code v1:accl_soil:<scheduleId>:<DRY|WET|UNKNOWN>}.
     *
     * <ul>
     *   <li>{@code DRY} → переключаем сообщение в обычный «как полил?» flow (#71)</li>
     *   <li>{@code WET} → откладываем nextDueAt на +1 день, edit «не поливаем»
     *       + кнопка «Напомнить через 2 дня»</li>
     *   <li>{@code UNKNOWN} → подсказка как проверить + snooze +1 день</li>
     * </ul>
     */
    private void handleAcclimationSoilAnswer(
            String[] parts, Long chatId, Integer messageId, String callbackId, TelegramClient client
    ) {
        if (parts.length != 4) {
            answerCallback(client, callbackId, "❌ Неверный формат");
            metricsService.recordCallback("accl_soil", CallbackOutcome.ERROR);
            return;
        }
        Long scheduleId;
        try {
            scheduleId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            answerCallback(client, callbackId, "❌ Неверный ID");
            metricsService.recordCallback("accl_soil", CallbackOutcome.ERROR);
            return;
        }
        String answer = parts[3];

        Optional<CareSchedule> opt = careScheduleRepository.findById(scheduleId);
        if (opt.isEmpty() || opt.get().getTaskType() != TaskType.WATERING) {
            answerCallback(client, callbackId, "❌ Расписание не найдено");
            metricsService.recordCallback("accl_soil", CallbackOutcome.ERROR);
            return;
        }
        CareSchedule schedule = opt.get();
        Plant plant = schedule.getPlant();
        if (plant.isArchived()) {
            editMessage(client, chatId, messageId, "Растение уже удалено");
            answerCallback(client, callbackId, "Растение удалено");
            metricsService.recordCallback("accl_soil", CallbackOutcome.IDEMPOTENT);
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        switch (answer) {
            case "DRY" -> {
                // Сухо → начинаем обычный «как полил?» flow (#71), переиспользуя
                // существующий askWateringAbundance (через startWateringDetailsFlow,
                // но с edit'ом исходного сообщения, не отдельным send).
                askWateringAbundance(plant.getName(), scheduleId, chatId, messageId, client);
                answerCallback(client, callbackId, "");
                metricsService.recordCallback("accl_soil", CallbackOutcome.OK);
            }
            case "WET" -> {
                // Влажно → откладываем полив на 1 день, не пишем CareHistory.
                schedule.setNextDueAt(now.plusDays(1));
                careScheduleRepository.save(schedule);
                editMessage(client, chatId, messageId,
                        "💧 Тогда пока не поливаем. Напомню завтра.",
                        buildAcclSnoozeKeyboard(scheduleId));
                answerCallback(client, callbackId, "Ок, отложил");
                metricsService.recordCallback("accl_soil", CallbackOutcome.OK);
                log.info("Acclimation WET: plant={} schedule={} snoozed +1d", plant.getId(), scheduleId);
            }
            case "UNKNOWN" -> {
                schedule.setNextDueAt(now.plusDays(1));
                careScheduleRepository.save(schedule);
                String hint = "🤷 Чтобы проверить — воткни палец или деревянную палочку "
                        + "на 2–3 см вглубь. Если земля прилипла или влажная — поливать рано.\n\n"
                        + "Напомню завтра.";
                editMessage(client, chatId, messageId, hint, buildAcclSnoozeKeyboard(scheduleId));
                answerCallback(client, callbackId, "");
                metricsService.recordCallback("accl_soil", CallbackOutcome.OK);
                log.info("Acclimation UNKNOWN: plant={} schedule={} snoozed +1d", plant.getId(), scheduleId);
            }
            default -> {
                answerCallback(client, callbackId, "❌ Неизвестный ответ");
                metricsService.recordCallback("accl_soil", CallbackOutcome.ERROR);
            }
        }
    }

    /**
     * «Напомнить через N дней» — простой ручной snooze в acclimation-flow.
     * callback: {@code v1:accl_snooze:<scheduleId>:<days>}.
     */
    private void handleAcclimationSnooze(
            String[] parts, Long chatId, Integer messageId, String callbackId, TelegramClient client
    ) {
        if (parts.length != 4) {
            answerCallback(client, callbackId, "❌ Неверный формат");
            metricsService.recordCallback("accl_snooze", CallbackOutcome.ERROR);
            return;
        }
        Long scheduleId;
        int days;
        try {
            scheduleId = Long.parseLong(parts[2]);
            days = Integer.parseInt(parts[3]);
        } catch (NumberFormatException e) {
            answerCallback(client, callbackId, "❌ Неверный формат");
            metricsService.recordCallback("accl_snooze", CallbackOutcome.ERROR);
            return;
        }
        if (days < 1 || days > 7) {
            answerCallback(client, callbackId, "❌ Неверный интервал");
            metricsService.recordCallback("accl_snooze", CallbackOutcome.ERROR);
            return;
        }

        Optional<CareSchedule> opt = careScheduleRepository.findById(scheduleId);
        if (opt.isEmpty()) {
            answerCallback(client, callbackId, "❌ Расписание не найдено");
            metricsService.recordCallback("accl_snooze", CallbackOutcome.ERROR);
            return;
        }
        CareSchedule schedule = opt.get();
        schedule.setNextDueAt(LocalDateTime.now().plusDays(days));
        careScheduleRepository.save(schedule);

        editMessage(client, chatId, messageId,
                "⏰ Ок, напомню через " + days + " " + dayWord(days) + ".");
        answerCallback(client, callbackId, "Отложено");
        metricsService.recordCallback("accl_snooze", CallbackOutcome.OK);
    }

    /**
     * Ответ на check-in вопрос «как растение?» (issue #75).
     * callback: {@code v1:accl_checkin:<plantId>:<OK|WILT|YELLOW>}.
     */
    private void handleAcclimationCheckin(
            String[] parts, Long chatId, Integer messageId, String callbackId, TelegramClient client
    ) {
        if (parts.length != 4) {
            answerCallback(client, callbackId, "❌ Неверный формат");
            metricsService.recordCallback("accl_checkin", CallbackOutcome.ERROR);
            return;
        }
        Long plantId;
        try {
            plantId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            answerCallback(client, callbackId, "❌ Неверный ID");
            metricsService.recordCallback("accl_checkin", CallbackOutcome.ERROR);
            return;
        }
        String answer = parts[3];

        Plant plant = plantAcclimationService.findById(plantId).orElse(null);
        if (plant == null) {
            answerCallback(client, callbackId, "❌ Растение не найдено");
            metricsService.recordCallback("accl_checkin", CallbackOutcome.ERROR);
            return;
        }
        if (plant.isArchived()) {
            editMessage(client, chatId, messageId, "Растение уже удалено");
            answerCallback(client, callbackId, "Растение удалено");
            metricsService.recordCallback("accl_checkin", CallbackOutcome.IDEMPOTENT);
            return;
        }

        String summary = switch (answer) {
            case "OK"     -> "👍 Отлично! Продолжаем в режиме акклиматизации.";
            case "WILT"   -> "🥀 Если вянет — чаще всего это перелив или сквозняк.\n"
                             + "Проверь: дренаж в горшке, не стоит ли на холодном подоконнике, "
                             + "не пересыхают ли края листьев. Если несколько дней не лучше — "
                             + "лучше пересадить в свежий грунт.";
            case "YELLOW" -> "🟡 Жёлтые листья при адаптации — нормально, особенно у нижних.\n"
                             + "Если массово — чаще всего: перелив, дефицит света или акклиматизация "
                             + "к низкой влажности. Дай растению ещё неделю, проверь, не сыро ли "
                             + "в горшке всё время.";
            default       -> null;
        };
        if (summary == null) {
            answerCallback(client, callbackId, "❌ Неизвестный ответ");
            metricsService.recordCallback("accl_checkin", CallbackOutcome.ERROR);
            return;
        }

        editMessage(client, chatId, messageId, summary);
        answerCallback(client, callbackId, "Записано");
        metricsService.recordCallback("accl_checkin", CallbackOutcome.OK);

        // Перепланируем следующий check-in через 3 дня (или вычистим, если режим скоро закончится).
        plantAcclimationService.scheduleNextCheckin(plant);

        log.info("Acclimation checkin: plant={} answer={}", plantId, answer);
    }

    private InlineKeyboardMarkup buildAcclSnoozeKeyboard(Long scheduleId) {
        InlineKeyboardButton in2 = InlineKeyboardButton.builder()
                .text("⏰ Через 2 дня")
                .callbackData("v1:accl_snooze:" + scheduleId + ":2")
                .build();
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(in2))
                .build();
    }

    private void editMessage(TelegramClient client, Long chatId, Integer messageId, String text,
                             InlineKeyboardMarkup keyboard) {
        EditMessageText edit = EditMessageText.builder()
                .chatId(chatId.toString())
                .messageId(messageId)
                .text(text)
                .replyMarkup(keyboard)
                .build();
        try {
            client.execute(edit);
        } catch (TelegramApiException e) {
            log.error("Failed to edit message: {}", e.getMessage(), e);
        }
    }

    private String dayWord(int days) {
        int mod10 = days % 10;
        int mod100 = days % 100;
        if (mod10 == 1 && mod100 != 11) return "день";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "дня";
        return "дней";
    }

    private void answerCallback(TelegramClient client, String callbackId, String text) {
        try {
            client.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to answer callback: {}", e.getMessage(), e);
        }
    }
}
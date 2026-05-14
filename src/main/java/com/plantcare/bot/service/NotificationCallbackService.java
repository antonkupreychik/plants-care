package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareHistory;
import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareHistoryRepository;
import com.plantcare.bot.repository.CareScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Обрабатывает callback'и от inline-кнопок уведомлений.
 * Формат callback_data: v1:{action}:{idOrPayload}
 * Действия:
 *   - done / snooze / skip — на расписании (scheduleId)
 *   - bulk_done            — массовый полив локации (locationId, issue #19)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationCallbackService {

    private static final int SNOOZE_HOURS = 2;
    private static final int DEDUP_SECONDS = 60;
    private static final int GRACE_PERIOD_HOURS = 24;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final CareScheduleRepository careScheduleRepository;
    private final CareHistoryRepository careHistoryRepository;
    private final PlantService plantService;
    private final UserService userService;

    @Transactional
    public void handleCallback(CallbackQuery callbackQuery, TelegramClient client) {
        String data = callbackQuery.getData();
        String callbackId = callbackQuery.getId();
        Long chatId = callbackQuery.getMessage().getChatId();
        Integer messageId = callbackQuery.getMessage().getMessageId();

        String[] parts = data.split(":");

        if (parts.length != 3) {
            answerCallback(client, callbackId, "❌ Неизвестная команда");
            return;
        }

        String action = parts[1];

        // Массовый полив локации (issue #19) — payload это locationId, а не scheduleId,
        // и логика своя, поэтому веткаем рано.
        if ("bulk_done".equals(action)) {
            handleBulkDone(parts[2], chatId, callbackId, client);
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

        Long scheduleId;
        try {
            scheduleId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            answerCallback(client, callbackId, "❌ Неверный ID");
            return;
        }

        Optional<CareSchedule> optSchedule = careScheduleRepository.findById(scheduleId);

        if (optSchedule.isEmpty()) {
            answerCallback(client, callbackId, "❌ Расписание не найдено");
            return;
        }

        CareSchedule schedule = optSchedule.get();
        Plant plant = schedule.getPlant();

        if (plant.isArchived()) {
            editMessage(client, chatId, messageId, "Растение уже удалено");
            answerCallback(client, callbackId, "Растение удалено");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        String responseText;
        String alertText;

        switch (action) {
            case "done" -> {
                boolean marked = markScheduleDone(schedule, now);

                if (!marked) {
                    answerCallback(client, callbackId, "Уже отмечено!");
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
                schedule.setNextDueAt(now.plusHours(SNOOZE_HOURS));
                careScheduleRepository.save(schedule);

                responseText = "⏰ " + plant.getName() + " — напомню через " + SNOOZE_HOURS + " часа";
                alertText = "Отложено!";
            }
            case "skip" -> {
                if (isDuplicateDone(plant, schedule, now)) {
                    answerCallback(client, callbackId, "Уже отмечено!");
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
                return;
            }
        }

        editMessage(client, chatId, messageId, responseText);
        answerCallback(client, callbackId, alertText);
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

        schedule.rescheduleFrom(now);
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
            return;
        }

        User user = userService.findByChatId(chatId).orElse(null);
        if (user == null) {
            log.warn("bulk_done callback from unknown chatId={}", chatId);
            answerCallback(client, callbackId, "❌ Сначала /start");
            return;
        }

        PlantService.BulkCareDoneResult result =
                plantService.markBulkCareDone(user.getId(), locationId, TaskType.WATERING);

        // Растений в локации с активным WATERING нет (либо чужая локация, либо все
        // расписания отключены, либо все растения архивированы).
        if (result.total() == 0) {
            answerCallback(client, callbackId, "В этой комнате пока нечего поливать");
            return;
        }

        // Все растения были политы недавно (< 60 сек) — это double-click по кнопке.
        if (result.updated() == 0) {
            answerCallback(client, callbackId, "Уже полито");
            return;
        }

        String resultText = "Готово! 🌿 Обновил график для "
                + result.updated() + " "
                + plantsPlural(result.updated())
                + " в локации " + result.locationName();
        sendMessage(client, chatId, resultText);
        answerCallback(client, callbackId, "Готово!");

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
        Long scheduleId;
        try {
            scheduleId = Long.parseLong(rawScheduleId);
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

        if (schedule.getTaskType() != TaskType.SOIL_CHECK) {
            answerCallback(client, callbackId, "❌ Неверный тип");
            return;
        }

        Plant plant = schedule.getPlant();
        if (plant.isArchived()) {
            editMessage(client, chatId, messageId, "🗑 Растение уже удалено.");
            answerCallback(client, callbackId, "");
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        // Дедуп — двойной тап. Дубликат считаем по последней SOIL_CHECK-записи.
        Optional<CareHistory> last = careHistoryRepository
                .findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(plant.getId(), TaskType.SOIL_CHECK);
        if (last.isPresent() && last.get().getDoneAt().plusSeconds(DEDUP_SECONDS).isAfter(now)) {
            answerCallback(client, callbackId, "Уже отмечено!");
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

        String nextDateStr = schedule.getNextDueAt().toLocalDate().format(DATE_FMT);

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
            return;
        }

        Optional<CareSchedule> opt = careScheduleRepository.findById(wateringScheduleId);
        if (opt.isEmpty() || opt.get().getTaskType() != TaskType.WATERING) {
            answerCallback(client, callbackId, "❌ Расписание полива не найдено");
            return;
        }
        CareSchedule wateringSchedule = opt.get();
        Plant plant = wateringSchedule.getPlant();

        if (plant.isArchived()) {
            editMessage(client, chatId, messageId, "🗑 Растение уже удалено.");
            answerCallback(client, callbackId, "");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        boolean marked = markScheduleDone(wateringSchedule, now);

        if (!marked) {
            answerCallback(client, callbackId, "Уже отмечено!");
            return;
        }

        String nextDateStr = wateringSchedule.getNextDueAt().toLocalDate().format(DATE_FMT);
        editMessage(client, chatId, messageId,
                "💧 Полил " + plant.getName() + ".\nСледующий полив — " + nextDateStr);
        answerCallback(client, callbackId, "Готово!");

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
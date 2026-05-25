package com.plantcare.bot.service;

import com.plantcare.core.service.BackdatedCareService;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.UserService;

import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.util.TimezoneSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Обработка inline-callback'ов flow «Я уже ухаживал» (ретро-отметка, issue #118).
 *
 * <p>Три callback-формата:
 * <ul>
 *   <li>{@code v1:back_care:<plantId>} — клик в карточке. Если у растения один
 *       активный тип ухода — сразу показываем выбор даты. Если несколько —
 *       сначала промежуточная клавиатура выбора типа.</li>
 *   <li>{@code v1:back_pick:<plantId>:<taskType>:<daysAgo>} — пресет даты
 *       0/1/2/3 дня назад.</li>
 *   <li>{@code v1:back_custom:<plantId>:<taskType>} — «Выбрать дату»; переводит
 *       юзера в состояние {@link ConversationState#AWAITING_CARE_DATE}, который
 *       обрабатывает {@code AwaitingCareDateStateHandler}.</li>
 * </ul>
 *
 * <p>Парсинг ввода даты в кастомном flow вынесен в {@link #parseCareDate}, чтобы
 * его можно было переиспользовать из state-handler'а.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackdatedCareCallbackService {

    /** Полная форма с годом — основной приоритет при парсинге. */
    public static final DateTimeFormatter DATE_FULL_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DAY_LABEL_FMT = DateTimeFormatter.ofPattern("dd.MM");

    /**
     * Порог «дата выглядит как ближайшее будущее, а не как прошлогодняя».
     * Если короткая дата без года попадает в текущий год и она в будущем,
     * но не дальше этого порога — НЕ роллим в прошлый год: скорее всего
     * юзер имел в виду «через пару дней», а не «декабрь прошлого года».
     * См. фикс #118 SHOULD-FIX 3.
     */
    private static final int NEAR_FUTURE_DAYS_THRESHOLD = 30;

    /** Состояния-ключи для stateData при ожидании кастомной даты. */
    public static final String STATE_PLANT_ID = "back_care_plant_id";
    public static final String STATE_TASK_TYPE = "back_care_task_type";

    private final PlantService plantService;
    private final UserService userService;
    private final BackdatedCareService backdatedCareService;
    private final Clock clock;

    /**
     * Единая точка входа из {@link NotificationCallbackService} — диспатч по action.
     */
    public void handleCallback(
            String action, String[] parts, Long chatId, Integer messageId,
            String callbackId, TelegramClient client
    ) {
        switch (action) {
            case "back_care"   -> handleBackCareStart(parts, chatId, messageId, callbackId, client);
            case "back_pick"   -> handleBackPickPreset(parts, chatId, messageId, callbackId, client);
            case "back_custom" -> handleBackPickCustom(parts, chatId, messageId, callbackId, client);
            default            -> answerCallback(client, callbackId, "❌ Неизвестное действие");
        }
    }

    // ====================================================================
    // STEP 1: клик «Я уже ухаживал» в карточке.
    // ====================================================================

    @Transactional
    public void handleBackCareStart(
            String[] parts, Long chatId, Integer messageId, String callbackId, TelegramClient client
    ) {
        if (parts.length != 3) {
            answerCallback(client, callbackId, "❌ Неверный формат");
            return;
        }
        Long plantId;
        try {
            plantId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            answerCallback(client, callbackId, "❌ Неверный ID");
            return;
        }

        User user = userService.findByChatId(chatId).orElse(null);
        if (user == null) {
            answerCallback(client, callbackId, "❌ Сначала /start");
            return;
        }

        Plant plant = plantService.getPlantForUser(user.getId(), plantId).orElse(null);
        if (plant == null) {
            answerCallback(client, callbackId, "❌ Растение не найдено");
            return;
        }

        List<TaskType> careTypes = activeCareTypes(plantId);
        if (careTypes.isEmpty()) {
            editMessage(client, chatId, messageId,
                    "У этого растения нет активных задач ухода — отмечать нечего.");
            answerCallback(client, callbackId, "");
            return;
        }

        if (careTypes.size() == 1) {
            showDateChoice(plantId, careTypes.get(0), chatId, messageId, client);
        } else {
            showTaskTypeChoice(plantId, careTypes, chatId, messageId, client);
        }
        answerCallback(client, callbackId, "");
    }

    /**
     * Если у растения несколько активных типов ухода — спрашиваем что именно
     * юзер сделал, прежде чем спросить когда. После выбора типа callback
     * пойдёт обратно как {@code v1:back_pick:<plantId>:<taskType>:<daysAgo>}
     * (без промежуточного шага «выбор даты»), но это уже делает дата-keyboard.
     */
    private void showTaskTypeChoice(
            Long plantId, List<TaskType> types, Long chatId, Integer messageId, TelegramClient client
    ) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        InlineKeyboardRow row = new InlineKeyboardRow();
        for (TaskType t : types) {
            row.add(InlineKeyboardButton.builder()
                    .text(taskEmoji(t) + " " + taskLabel(t))
                    // Сразу прыгаем в date-pick, но без выбранной даты — следующий
                    // шаг показывает date keyboard. Используем синтетический daysAgo=-1
                    // как маркер «покажи date keyboard»; чтобы не плодить новый action.
                    .callbackData("v1:back_pick:" + plantId + ":" + t.name() + ":-1")
                    .build());
        }
        rows.add(row);
        rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder()
                .text("◀️ Назад")
                .callbackData("PLANT:VIEW:" + plantId)
                .build()));

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder().keyboard(rows).build();
        editMessage(client, chatId, messageId,
                "Что именно ты сделал?", keyboard);
    }

    private void showDateChoice(
            Long plantId, TaskType taskType, Long chatId, Integer messageId, TelegramClient client
    ) {
        InlineKeyboardMarkup keyboard = buildDateChoiceKeyboard(plantId, taskType);
        editMessage(client, chatId, messageId,
                "Когда ты " + doneVerb(taskType).toLowerCase() + "?",
                keyboard);
    }

    private InlineKeyboardMarkup buildDateChoiceKeyboard(Long plantId, TaskType taskType) {
        String base = "v1:back_pick:" + plantId + ":" + taskType.name() + ":";
        InlineKeyboardButton today = InlineKeyboardButton.builder()
                .text("Сегодня").callbackData(base + "0").build();
        InlineKeyboardButton yesterday = InlineKeyboardButton.builder()
                .text("Вчера").callbackData(base + "1").build();
        InlineKeyboardButton dayBeforeYesterday = InlineKeyboardButton.builder()
                .text("Позавчера").callbackData(base + "2").build();
        InlineKeyboardButton threeDaysAgo = InlineKeyboardButton.builder()
                .text("3 дня назад").callbackData(base + "3").build();
        InlineKeyboardButton custom = InlineKeyboardButton.builder()
                .text("📅 Выбрать дату")
                .callbackData("v1:back_custom:" + plantId + ":" + taskType.name())
                .build();
        InlineKeyboardButton back = InlineKeyboardButton.builder()
                .text("◀️ Назад")
                .callbackData("PLANT:VIEW:" + plantId)
                .build();
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(today, yesterday))
                .keyboardRow(new InlineKeyboardRow(dayBeforeYesterday, threeDaysAgo))
                .keyboardRow(new InlineKeyboardRow(custom))
                .keyboardRow(new InlineKeyboardRow(back))
                .build();
    }

    // ====================================================================
    // STEP 2: выбор пресетной даты или маркер «-1 → покажи date keyboard».
    // ====================================================================

    @Transactional
    public void handleBackPickPreset(
            String[] parts, Long chatId, Integer messageId, String callbackId, TelegramClient client
    ) {
        if (parts.length != 5) {
            answerCallback(client, callbackId, "❌ Неверный формат");
            return;
        }
        Long plantId;
        int daysAgo;
        TaskType taskType;
        try {
            plantId = Long.parseLong(parts[2]);
            taskType = TaskType.valueOf(parts[3]);
            daysAgo = Integer.parseInt(parts[4]);
        } catch (IllegalArgumentException e) {
            answerCallback(client, callbackId, "❌ Неверные параметры");
            return;
        }

        User user = userService.findByChatId(chatId).orElse(null);
        if (user == null) {
            answerCallback(client, callbackId, "❌ Сначала /start");
            return;
        }
        Plant plant = plantService.getPlantForUser(user.getId(), plantId).orElse(null);
        if (plant == null) {
            answerCallback(client, callbackId, "❌ Растение не найдено");
            return;
        }

        // -1 — это маркер из showTaskTypeChoice «таск выбран, покажи даты».
        if (daysAgo < 0) {
            showDateChoice(plantId, taskType, chatId, messageId, client);
            answerCallback(client, callbackId, "");
            return;
        }
        if (daysAgo > BackdatedCareService.MAX_BACKDATE_DAYS) {
            answerCallback(client, callbackId, "❌ Слишком давно");
            return;
        }

        ZoneId zone = TimezoneSupport.zoneOf(user);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate doneDate = today.minusDays(daysAgo);

        applyResult(user, plant, taskType, doneDate, chatId, messageId, callbackId, client);
    }

    // ====================================================================
    // STEP 2b: «Выбрать дату» — уводим юзера в state, ждём ввод DD.MM[.YYYY].
    // ====================================================================

    @Transactional
    public void handleBackPickCustom(
            String[] parts, Long chatId, Integer messageId, String callbackId, TelegramClient client
    ) {
        if (parts.length != 4) {
            answerCallback(client, callbackId, "❌ Неверный формат");
            return;
        }
        Long plantId;
        TaskType taskType;
        try {
            plantId = Long.parseLong(parts[2]);
            taskType = TaskType.valueOf(parts[3]);
        } catch (IllegalArgumentException e) {
            answerCallback(client, callbackId, "❌ Неверные параметры");
            return;
        }

        User user = userService.findByChatId(chatId).orElse(null);
        if (user == null) {
            answerCallback(client, callbackId, "❌ Сначала /start");
            return;
        }
        Plant plant = plantService.getPlantForUser(user.getId(), plantId).orElse(null);
        if (plant == null) {
            answerCallback(client, callbackId, "❌ Растение не найдено");
            return;
        }

        userService.setStateData(user, STATE_PLANT_ID, String.valueOf(plantId));
        userService.setStateData(user, STATE_TASK_TYPE, taskType.name());
        userService.updateState(user, ConversationState.AWAITING_CARE_DATE);

        editMessage(client, chatId, messageId,
                "📅 Введи дату в формате DD.MM или DD.MM.YYYY.\n"
                + "Например: 22.05 или 22.05.2026.\n"
                + "Можно не больше " + BackdatedCareService.MAX_BACKDATE_DAYS + " дней назад.\n"
                + "/cancel — отменить.");
        answerCallback(client, callbackId, "");
    }

    // ====================================================================
    // Финал: создание записи + ответ юзеру.
    // ====================================================================

    /**
     * Записывает ретро-уход и формирует пользовательский текст.
     * Используется и из {@link #handleBackPickPreset}, и из state-handler'а
     * после успешного парсинга даты — вынесен сюда, чтобы оба пути дали
     * одинаковый формат подтверждения.
     *
     * <p>Метод сам выбирает: editMessage (если есть messageId — кнопочный flow)
     * или sendMessage (если messageId == null — flow через текстовый ввод).
     */
    @Transactional
    public void applyResult(
            User user, Plant plant, TaskType taskType, LocalDate doneDate,
            Long chatId, Integer messageId, String callbackId, TelegramClient client
    ) {
        BackdatedCareService.BackdatedCareResult result =
                backdatedCareService.recordBackdatedCare(plant, taskType, doneDate);

        String text = switch (result.outcome()) {
            case CREATED -> {
                String dayStr = result.doneDate().format(DAY_LABEL_FMT);
                String nextStr = result.nextDueAt() == null
                        ? "(расписание этого типа неактивно)"
                        : TimezoneSupport.dateInUserZone(result.nextDueAt(), user).format(DATE_FULL_FMT);
                yield "✅ Отмечено: " + taskLabel(taskType) + " " + dayStr + ".\n"
                        + "Следующее: " + nextStr;
            }
            case ALREADY_RECORDED -> "За " + result.doneDate().format(DAY_LABEL_FMT) + " уже отмечено.";
            case TOO_OLD          -> "❌ Можно отметить не больше "
                    + BackdatedCareService.MAX_BACKDATE_DAYS + " дней назад.";
            case IN_FUTURE        -> "❌ Дата " + result.doneDate().format(DATE_FULL_FMT)
                    + " в будущем. Если имели в виду прошлый год — укажи дату с годом, "
                    + "например " + result.doneDate().minusYears(1).format(DATE_FULL_FMT) + ".";
        };

        if (messageId != null) {
            editMessage(client, chatId, messageId, text);
        } else {
            sendMessage(client, chatId, text);
        }
        if (callbackId != null) {
            answerCallback(client, callbackId, result.isCreated() ? "Записано" : "");
        }
    }

    // ====================================================================
    // Парсинг даты для AwaitingCareDateStateHandler.
    // ====================================================================

    /**
     * Парсинг DD.MM или DD.MM.YYYY, контекст «сегодня в TZ юзера».
     *
     * <p>Правила:
     * <ul>
     *   <li>DD.MM — год = текущий. Если получается дата в будущем:
     *     <ul>
     *       <li>в ближайшем будущем (≤ {@link #NEAR_FUTURE_DAYS_THRESHOLD} дней) —
     *           НЕ роллим в прошлый год: считаем, что юзер опечатался и имел в виду
     *           ближайшие дни. {@code applyResult} вернёт {@code IN_FUTURE}.</li>
     *       <li>в дальнем будущем (например, юзер 1 января пишет «28.12» — это
     *           ~360 дней) — считаем прошлогодней датой и роллим на год назад.</li>
     *     </ul>
     *   </li>
     *   <li>DD.MM.YYYY — используем как есть.</li>
     *   <li>Невалидный формат / несуществующая дата → {@link Optional#empty()}.</li>
     * </ul>
     */
    public Optional<LocalDate> parseCareDate(String raw, User user) {
        if (raw == null) return Optional.empty();
        String s = raw.trim();
        if (s.isEmpty()) return Optional.empty();

        ZoneId zone = TimezoneSupport.zoneOf(user);
        LocalDate today = LocalDate.now(clock.withZone(zone));

        // Сначала пробуем полный формат — иначе DD.MM.YYYY распарсится как DD.MM
        // (короткий формат на префиксе) и год потеряется.
        try {
            return Optional.of(LocalDate.parse(s, DATE_FULL_FMT));
        } catch (Exception ignored) {
            // не полный формат — пробуем короткий
        }
        try {
            LocalDate shortDate = LocalDate.parse(s + "." + today.getYear(), DATE_FULL_FMT);
            // Если получилось в будущем — есть две альтернативные интерпретации:
            //   1) юзер написал ближайшее число завтрашнего/послезавтрашнего дня
            //      и просто запутался → оставляем как есть, applyResult выдаст IN_FUTURE.
            //   2) юзер пишет в начале января «28.12», имея в виду декабрь прошлого года.
            // Различаем по расстоянию: «28.12» из января = ~360 дней в будущем →
            // ролим в прошлый год; «25.05» из 24.05 = 1 день → НЕ ролим
            // (см. фикс #118 SHOULD-FIX 3, silent TOO_OLD на дате «выглядит как завтра»).
            if (shortDate.isAfter(today.plusDays(NEAR_FUTURE_DAYS_THRESHOLD))) {
                shortDate = shortDate.minusYears(1);
            }
            return Optional.of(shortDate);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ====================================================================
    // Хелперы.
    // ====================================================================

    private List<TaskType> activeCareTypes(Long plantId) {
        return plantService.getActiveSchedules(plantId).stream()
                .map(CareSchedule::getTaskType)
                .filter(t -> t != TaskType.SOIL_CHECK)
                .distinct()
                .toList();
    }

    private String taskLabel(TaskType taskType) {
        return switch (taskType) {
            case WATERING    -> "полив";
            case MISTING     -> "опрыскивание";
            case FERTILIZING -> "удобрение";
            case SOIL_CHECK  -> "проверка грунта";
        };
    }

    private String doneVerb(TaskType taskType) {
        return switch (taskType) {
            case WATERING    -> "Полил";
            case MISTING     -> "Опрыскал";
            case FERTILIZING -> "Удобрил";
            case SOIL_CHECK  -> "Проверил";
        };
    }

    private String taskEmoji(TaskType taskType) {
        return switch (taskType) {
            case WATERING    -> "💧";
            case MISTING     -> "💦";
            case FERTILIZING -> "🌱";
            case SOIL_CHECK  -> "🌡";
        };
    }

    private void editMessage(TelegramClient client, Long chatId, Integer messageId, String text) {
        editMessage(client, chatId, messageId, text, null);
    }

    private void editMessage(
            TelegramClient client, Long chatId, Integer messageId, String text,
            InlineKeyboardMarkup keyboard
    ) {
        EditMessageText edit = EditMessageText.builder()
                .chatId(chatId.toString())
                .messageId(messageId)
                .text(text)
                .replyMarkup(keyboard)
                .build();
        try {
            client.execute(edit);
        } catch (TelegramApiException e) {
            log.error("Failed to edit backdated-care message: {}", e.getMessage(), e);
        }
    }

    private void sendMessage(TelegramClient client, Long chatId, String text) {
        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .build();
        try {
            client.execute(msg);
        } catch (TelegramApiException e) {
            log.error("Failed to send backdated-care message: {}", e.getMessage(), e);
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

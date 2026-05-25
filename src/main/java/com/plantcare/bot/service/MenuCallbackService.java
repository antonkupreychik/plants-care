package com.plantcare.bot.service;

import com.plantcare.bot.diagnosis.PlantDiagnosisService;
import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.PlantTemplate;
import com.plantcare.bot.domain.Species;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.domain.enums.PhotoProgressFrequency;
import com.plantcare.bot.domain.enums.PlantEventType;
import com.plantcare.bot.domain.enums.Season;
import com.plantcare.bot.domain.enums.SeasonalMode;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.seasonal.service.SeasonalMenuService;
import com.plantcare.bot.state.impl.AwaitingProgressPhotoStateHandler;
import com.plantcare.bot.util.TimezoneSupport;
import com.plantcare.bot.weather.service.WeatherMenuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MenuCallbackService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter QUIET_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private static final EnumSet<ConversationState> EDIT_MODE_STATES = EnumSet.of(
            ConversationState.AWAITING_PLANT_RENAME,
            ConversationState.AWAITING_PLANT_NOTE,
            ConversationState.AWAITING_PLANT_PHOTO_EDIT,
            ConversationState.AWAITING_NEW_INTERVAL,
            ConversationState.AWAITING_PLANT_DIAGNOSIS
    );

    private final UserService userService;
    private final PlantService plantService;
    private final LocationMenuService locationMenuService;
    private final LocationService locationService;
    private final MainMenuService mainMenuService;
    private final PlantMenuService plantMenuService;
    private final PlantCardService plantCardService;
    private final CalendarMenuService calendarMenuService;
    private final CalendarExportService calendarExportService;
    private final PlantTemplateService plantTemplateService;
    private final NotificationCallbackService notificationCallbackService;
    private final PlantEventService plantEventService;
    private final PlantAcclimationService plantAcclimationService;
    private final PhotoProgressService photoProgressService;
    private final PhotoProgressCardService photoProgressCardService;
    private final PlantDiagnosisService plantDiagnosisService;
    private final WeatherMenuService weatherMenuService;
    private final PlantArchiveService plantArchiveService;
    private final PlantArchiveMenuService plantArchiveMenuService;
    private final ShoppingListService shoppingListService;
    private final ShoppingListMenuService shoppingListMenuService;
    private final DiseaseMenuService diseaseMenuService;
    private final SeasonalMenuService seasonalMenuService;
    private final UserSettingsService userSettingsService;

    private record LocationPreset(String name, String emoji) {
    }

    public void handleCallback(CallbackQuery callbackQuery, TelegramClient client, User user) {
        String data = callbackQuery.getData();
        String callbackId = callbackQuery.getId();
        Long chatId = callbackQuery.getMessage().getChatId();
        Integer messageId = callbackQuery.getMessage().getMessageId();

        if (data == null || data.isBlank()) {
            answerCallback(client, callbackId, "❌ Пустая команда");
            return;
        }

        if (data.startsWith("MENU:")) {
            handleMenuCallback(data, callbackId, chatId, messageId, client, user);
            return;
        }

        if (data.startsWith("LOCATION:")) {
            handleLocationCallback(data, callbackId, client, user);
            return;
        }

        if (data.startsWith("PLANT:")) {
            handlePlantCallback(data, callbackId, messageId, client, user);
            return;
        }

        // issue #117: архив / memorial.
        if (data.startsWith("ARCHIVE:")) {
            handleArchiveCallback(data, callbackId, messageId, client, user);
            return;
        }

        // Фото-прогресс (issue #72). См. handlePhotoProgressCallback для полного списка.
        if (data.startsWith("PHOTO_PROGRESS:")) {
            handlePhotoProgressCallback(data, callbackId, messageId, client, user);
            return;
        }

        // Справочник болезней (issue #140).
        if (data.startsWith("DISEASE:")) {
            handleDiseaseCallback(data, callbackId, client, user);
            return;
        }

        if (data.startsWith("TPL_")) {
            handleTemplateCallback(data, callbackId, client, user);
            return;
        }

        if (data.startsWith("cal:week:")) {
            handleCalendarWeekCallback(data, callbackId, messageId, client, user);
            return;
        }

        if (data.startsWith("WEATHER:")) {
            handleWeatherCallback(data, callbackId, messageId, client, user);
            return;
        }

        // Список покупок (issue #136).
        if (data.startsWith("SHOPPING:")) {
            handleShoppingCallback(data, callbackId, client, user);
            return;
        }

        // Сезонные интервалы (issue #67) — экран глобальных настроек сезонности.
        if (data.startsWith("SEASON:")) {
            handleSeasonalCallback(data, callbackId, messageId, client, user);
            return;
        }

        answerCallback(client, callbackId, "❌ Неизвестная команда");
    }

    /**
     * Сезонные интервалы (issue #67). Разбирает callback'и экрана
     * {@link SeasonalMenuService}: {@code SEASON:TOGGLE}, {@code SEASON:MODE:<MODE>},
     * {@code SEASON:MUL:<SEASON>}, {@code SEASON:INT:<SEASON>} и
     * {@code SEASON:INT:<SEASON>:CLEAR}.
     */
    private void handleSeasonalCallback(
            String data,
            String callbackId,
            Integer messageId,
            TelegramClient client,
            User user
    ) {
        String action = data.substring("SEASON:".length());

        if ("TOGGLE".equals(action)) {
            seasonalMenuService.toggleEnabled(user, messageId, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (action.startsWith("MODE:")) {
            String modeName = action.substring("MODE:".length());
            try {
                seasonalMenuService.setMode(user, SeasonalMode.valueOf(modeName), messageId, client);
                answerCallback(client, callbackId, "");
            } catch (IllegalArgumentException e) {
                answerCallback(client, callbackId, "❌ Неизвестный режим");
            }
            return;
        }

        if (action.startsWith("MUL:")) {
            Season season = parseSeasonToken(action.substring("MUL:".length()));
            if (season == null) {
                answerCallback(client, callbackId, "❌ Неизвестный сезон");
                return;
            }
            seasonalMenuService.cycleMultiplier(user, season, messageId, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (action.startsWith("INT:")) {
            String rest = action.substring("INT:".length());
            boolean clear = rest.endsWith(":CLEAR");
            String seasonToken = clear ? rest.substring(0, rest.length() - ":CLEAR".length()) : rest;
            Season season = parseSeasonToken(seasonToken);
            if (season == null) {
                answerCallback(client, callbackId, "❌ Неизвестный сезон");
                return;
            }
            if (clear) {
                seasonalMenuService.clearInterval(user, season, messageId, client);
            } else {
                seasonalMenuService.cycleInterval(user, season, messageId, client);
            }
            answerCallback(client, callbackId, "");
            return;
        }

        answerCallback(client, callbackId, "❌ Неизвестная команда");
    }

    private static Season parseSeasonToken(String token) {
        return switch (token) {
            case "SUMMER" -> Season.SUMMER;
            case "WINTER" -> Season.WINTER;
            default -> null;
        };
    }

    private void handleWeatherCallback(
            String data,
            String callbackId,
            Integer messageId,
            TelegramClient client,
            User user
    ) {
        String action = data.substring("WEATHER:".length());

        switch (action) {
            case "TOGGLE" -> {
                weatherMenuService.toggleEnabled(user, messageId, client);
                answerCallback(client, callbackId, "");
            }
            case "LOCATION" -> {
                weatherMenuService.promptForLocation(user, client);
                answerCallback(client, callbackId, "");
            }
            default -> answerCallback(client, callbackId, "❌ Неизвестная команда");
        }
    }

    private void handleCalendarWeekCallback(
            String data,
            String callbackId,
            Integer messageId,
            TelegramClient client,
            User user
    ) {
        int offset;

        try {
            offset = Integer.parseInt(data.substring("cal:week:".length()));
        } catch (NumberFormatException e) {
            answerCallback(client, callbackId, "❌ Неверный offset");
            return;
        }

        calendarMenuService.sendCalendar(user, offset, messageId, client);
        answerCallback(client, callbackId, "");
    }

    private void handleMenuCallback(
            String data,
            String callbackId,
            Long chatId,
            Integer messageId,
            TelegramClient client,
            User user
    ) {
        String action = data.substring("MENU:".length());

        switch (action) {
            case "ADD_PLANT" -> {
                userService.updateState(user, ConversationState.AWAITING_PLANT_SPECIES_CHOICE);

                List<Species> popular = plantService.getPopularSpecies(6);

                SendMessage message = SendMessage.builder()
                        .chatId(chatId.toString())
                        .text(" Давай добавим новое растение!\n\nЧто за растение? Вот популярные виды:")
                        .replyMarkup(buildSpeciesKeyboard(popular))
                        .build();

                try {
                    client.execute(message);
                } catch (TelegramApiException e) {
                    log.error("Failed to start add plant from menu", e);
                }

                answerCallback(client, callbackId, "");
            }
            case "LOCATIONS" -> {
                locationMenuService.sendLocationsMenu(user, client);
                answerCallback(client, callbackId, "");
            }
            case "WEATHER" -> {
                weatherMenuService.sendWeatherScreen(user, messageId, client);
                answerCallback(client, callbackId, "");
            }
            case "SEASONAL" -> {
                seasonalMenuService.sendScreen(user, messageId, client);
                answerCallback(client, callbackId, "");
            }
            case "BACK" -> {
                mainMenuService.sendMainMenu(user, client);
                answerCallback(client, callbackId, "");
            }
            case "ALL_PLANTS" -> {
                plantMenuService.sendMyPlantsList(user, null, client);
                answerCallback(client, callbackId, "");
            }
            case "CALENDAR" -> {
                calendarMenuService.sendCalendar(user, client);
                answerCallback(client, callbackId, "");
            }
            case "DISEASES" -> {
                diseaseMenuService.sendList(user, client);
                answerCallback(client, callbackId, "");
            }
            case "SETTINGS" -> {
                sendSettingsMenu(user, client);
                answerCallback(client, callbackId, "");
            }
            case "SHOPPING_LIST" -> {
                shoppingListMenuService.sendShoppingList(user, client);
                answerCallback(client, callbackId, "");
            }
            case "MY_TEMPLATES" -> {
                sendMyTemplatesList(user, client);
                answerCallback(client, callbackId, "");
            }
            case "CHANGE_TZ" -> {
                userService.updateState(user, ConversationState.AWAITING_TIMEZONE);
                sendTimezonePrompt(user, client);
                answerCallback(client, callbackId, "");
            }

            // ===== Тихие часы (issue #116) =====
            case "QUIET_HOURS" -> {
                sendQuietHoursMenu(user, client);
                answerCallback(client, callbackId, "");
            }
            case "QUIET_START" -> {
                userService.updateState(user, ConversationState.AWAITING_QUIET_START);
                sendText(user, client, "Введи время начала тихих часов в формате ЧЧ:ММ (например 22:00):");
                answerCallback(client, callbackId, "");
            }
            case "QUIET_END" -> {
                userService.updateState(user, ConversationState.AWAITING_QUIET_END);
                sendText(user, client, "Введи время конца тихих часов в формате ЧЧ:ММ (например 09:00):");
                answerCallback(client, callbackId, "");
            }
            case "QUIET_RESET" -> {
                userSettingsService.resetQuietHours(user);
                sendText(user, client, "🌙 Тихие часы сброшены: 22:00–09:00.");
                sendQuietHoursMenu(user, client);
                answerCallback(client, callbackId, "");
            }

            case "CALENDAR_EXPORT" -> {
                sendCalendarExport(user, client);
                answerCallback(client, callbackId, "");
            }

            // ===== Отпуск (issue #53) =====
            case "VACATION" -> {
                sendVacationDaysMenu(user, client);
                answerCallback(client, callbackId, "");
            }
            case "VACATION_3" -> {
                startVacation(user, client, 3);
                answerCallback(client, callbackId, "");
            }
            case "VACATION_7" -> {
                startVacation(user, client, 7);
                answerCallback(client, callbackId, "");
            }
            case "VACATION_14" -> {
                startVacation(user, client, 14);
                answerCallback(client, callbackId, "");
            }
            case "VACATION_OTHER" -> {
                userService.updateState(user, ConversationState.AWAITING_VACATION_DAYS);
                sendText(user, client, "Введи число дней от "
                        + UserService.MIN_VACATION_DAYS + " до " + UserService.MAX_VACATION_DAYS + ":");
                answerCallback(client, callbackId, "");
            }
            case "VACATION_END" -> {
                endVacation(user, client);
                answerCallback(client, callbackId, "");
            }
            case "VACATION_EXTEND_3" -> {
                extendVacation(user, client, 3);
                answerCallback(client, callbackId, "");
            }
            case "VACATION_EXTEND_7" -> {
                extendVacation(user, client, 7);
                answerCallback(client, callbackId, "");
            }

            default -> answerCallback(client, callbackId, "❌ Неизвестная команда");
        }
    }

    private void handleLocationCallback(
            String data,
            String callbackId,
            TelegramClient client,
            User user
    ) {
        if ("LOCATION:CREATE".equals(data)) {
            if (locationService.hasReachedLocationsLimit(user.getId())) {
                answerCallback(
                        client,
                        callbackId,
                        "❌ Можно создать максимум " + locationService.getMaxLocationsPerUser() + " комнат"
                );
                return;
            }

            sendLocationPresetMenu(user, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("LOCATION:PRESET:")) {
            String presetKey = data.substring("LOCATION:PRESET:".length());

            try {
                LocationPreset preset = getLocationPreset(presetKey);

                locationService.createLocation(
                        user,
                        preset.name(),
                        preset.emoji()
                );

                SendMessage message = SendMessage.builder()
                        .chatId(user.getTelegramChatId().toString())
                        .text("✅ Комната создана: " + preset.emoji() + " " + preset.name())
                        .build();

                try {
                    client.execute(message);
                } catch (TelegramApiException e) {
                    log.error("Failed to send location created message", e);
                }

                locationMenuService.sendLocationsMenu(user, client);
                answerCallback(client, callbackId, "");
                return;
            } catch (IllegalArgumentException e) {
                answerCallback(client, callbackId, "❌ " + e.getMessage());
                return;
            } catch (Exception e) {
                log.error("Failed to create preset location", e);
                answerCallback(client, callbackId, "❌ Не удалось создать комнату");
                return;
            }
        }

        if ("LOCATION:CUSTOM_NAME".equals(data)) {
            userService.updateState(user, ConversationState.AWAITING_LOCATION_NAME);

            SendMessage message = SendMessage.builder()
                    .chatId(user.getTelegramChatId().toString())
                    .text("Как назовём комнату?\n\nНапример: Кухня, Балкон, Спальня")
                    .build();

            try {
                client.execute(message);
            } catch (TelegramApiException e) {
                log.error("Failed to ask location name", e);
            }

            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("LOCATION:VIEW:")) {
            Long locationId = Long.parseLong(data.substring("LOCATION:VIEW:".length()));
            locationMenuService.sendLocationScreen(user, locationId, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("LOCATION:RENAME:")) {
            Long locationId = Long.parseLong(data.substring("LOCATION:RENAME:".length()));

            userService.setStateData(user, "editing_location_id", String.valueOf(locationId));
            userService.updateState(user, ConversationState.AWAITING_LOCATION_RENAME);

            SendMessage message = SendMessage.builder()
                    .chatId(user.getTelegramChatId().toString())
                    .text("Введи новое название комнаты.\n\nНазвание должно быть от 1 до 30 символов.")
                    .build();

            try {
                client.execute(message);
            } catch (TelegramApiException e) {
                log.error("Failed to ask location rename", e);
            }

            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("LOCATION:EMOJI:")) {
            Long locationId = Long.parseLong(data.substring("LOCATION:EMOJI:".length()));

            userService.setStateData(user, "editing_location_id", String.valueOf(locationId));
            userService.updateState(user, ConversationState.AWAITING_LOCATION_CHANGE_EMOJI);

            SendMessage message = SendMessage.builder()
                    .chatId(user.getTelegramChatId().toString())
                    .text("Выбери новый emoji для комнаты или отправь свой одним сообщением:")
                    .replyMarkup(buildEmojiKeyboard("LOCATION_CHANGE_EMOJI:"))
                    .build();

            try {
                client.execute(message);
            } catch (TelegramApiException e) {
                log.error("Failed to ask location emoji", e);
            }

            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("LOCATION:DELETE_CONFIRM:")) {
            String payload = data.substring("LOCATION:DELETE_CONFIRM:".length());
            String[] parts = payload.split(":");

            Long locationId = Long.parseLong(parts[0]);
            Long targetLocationId = Long.parseLong(parts[1]);

            try {
                long movedPlantsCount = locationService.countPlantsInLocation(user.getId(), locationId);

                locationService.deleteLocation(
                        user.getId(),
                        locationId,
                        targetLocationId
                );

                SendMessage message = SendMessage.builder()
                        .chatId(user.getTelegramChatId().toString())
                        .text("✅ Комната удалена.\nРастений перенесено: " + movedPlantsCount)
                        .build();

                try {
                    client.execute(message);
                } catch (TelegramApiException e) {
                    log.error("Failed to send location deleted message", e);
                }

                locationMenuService.sendLocationsMenu(user, client);
                answerCallback(client, callbackId, "");
                return;
            } catch (IllegalArgumentException e) {
                answerCallback(client, callbackId, "❌ " + e.getMessage());
                return;
            }
        }

        if (data.startsWith("LOCATION:DELETE:")) {
            Long locationId = Long.parseLong(data.substring("LOCATION:DELETE:".length()));
            locationMenuService.sendDeleteLocationDialog(user, locationId, client);
            answerCallback(client, callbackId, "");
            return;
        }

        answerCallback(client, callbackId, "❌ Неизвестная команда");
    }

    /**
     * Список покупок (issue #136).
     *
     * <p>Поддерживаемые форматы:
     * <ul>
     *   <li>{@code SHOPPING:ADD} — перевод в AWAITING_SHOPPING_ITEM</li>
     *   <li>{@code SHOPPING:CLEAR} — удалить отмеченные позиции</li>
     *   <li>{@code SHOPPING:CHECK:{id}} — пометить купленной (target=true)</li>
     *   <li>{@code SHOPPING:UNCHECK:{id}} — снять отметку (target=false)</li>
     * </ul>
     * Toggle принимает явный целевой статус, поэтому двойной тап идемпотентен.
     */
    private void handleShoppingCallback(
            String data,
            String callbackId,
            TelegramClient client,
            User user
    ) {
        if ("SHOPPING:ADD".equals(data)) {
            userService.updateState(user, ConversationState.AWAITING_SHOPPING_ITEM);
            sendText(user, client, "🛒 Что добавить в список покупок?");
            answerCallback(client, callbackId, "");
            return;
        }

        if ("SHOPPING:CLEAR".equals(data)) {
            long removed = shoppingListService.clearChecked(user.getId());

            if (removed == 0) {
                answerCallback(client, callbackId, "Нечего убирать — нет отмеченных");
                return;
            }

            shoppingListMenuService.sendShoppingList(user, client);
            answerCallback(client, callbackId, "🗑 Убрано: " + removed);
            return;
        }

        if (data.startsWith("SHOPPING:CHECK:") || data.startsWith("SHOPPING:UNCHECK:")) {
            boolean targetChecked = data.startsWith("SHOPPING:CHECK:");
            String prefix = targetChecked ? "SHOPPING:CHECK:" : "SHOPPING:UNCHECK:";

            Long itemId = parseLong(data.substring(prefix.length()));

            if (itemId == null) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }

            try {
                shoppingListService.toggle(user.getId(), itemId, targetChecked);
            } catch (IllegalArgumentException e) {
                answerCallback(client, callbackId, "❌ " + e.getMessage());
                return;
            }

            shoppingListMenuService.sendShoppingList(user, client);
            answerCallback(client, callbackId, "");
            return;
        }

        answerCallback(client, callbackId, "❌ Неизвестная команда");
    }

    private void handlePlantCallback(
            String data,
            String callbackId,
            Integer messageId,
            TelegramClient client,
            User user
    ) {
        if (plantDiagnosisService != null && plantDiagnosisService.supports(data)) {
            plantDiagnosisService.handleCallbackByIds(data, callbackId, client, user);
            return;
        }

        if (EDIT_MODE_STATES.contains(user.getConversationState())) {
            userService.resetToIdle(user);
        }

        if ("PLANT:LIST".equals(data)) {
            plantMenuService.sendMyPlantsList(user, messageId, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("PLANT:VIEW:")) {
            String[] parts = data.substring("PLANT:VIEW:".length()).split(":");

            Long plantId;

            try {
                plantId = Long.parseLong(parts[0]);
            } catch (NumberFormatException e) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }

            String backTarget = parseBackTarget(parts, 1);

            plantCardService.showPlantCard(user, plantId, messageId, backTarget, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("PLANT:CARE:")) {
            String[] parts = data.substring("PLANT:CARE:".length()).split(":");

            if (parts.length < 2) {
                answerCallback(client, callbackId, "❌ Неверная команда");
                return;
            }

            Long plantId;
            TaskType taskType;

            try {
                plantId = Long.parseLong(parts[0]);
                taskType = TaskType.valueOf(parts[1]);
            } catch (IllegalArgumentException e) {
                answerCallback(client, callbackId, "❌ Неверная команда");
                return;
            }

            try {
                if (taskType == TaskType.WATERING) {
                    Plant plant = plantService
                            .getPlantForUser(user.getId(), plantId)
                            .orElse(null);

                    if (plant == null) {
                        answerCallback(client, callbackId, "❌ Растение не найдено");
                        return;
                    }

                    CareSchedule wateringSchedule = plantService
                            .getActiveSchedules(plantId)
                            .stream()
                            .filter(schedule -> schedule.getTaskType() == TaskType.WATERING)
                            .findFirst()
                            .orElse(null);

                    if (wateringSchedule == null) {
                        answerCallback(client, callbackId, "❌ Расписание не настроено");
                        return;
                    }

                    notificationCallbackService.startWateringDetailsFlow(
                            wateringSchedule.getId(),
                            plant.getName(),
                            user.getTelegramChatId(),
                            client
                    );

                    answerCallback(client, callbackId, "");
                    return;
                }

                PlantService.MarkCareDoneResult result = plantService.markCareDone(user.getId(), plantId, taskType);

                if (result == null) {
                    answerCallback(client, callbackId, "❌ Расписание не настроено");
                    return;
                }

                if (result.wasDuplicate()) {
                    answerCallback(client, callbackId, "Уже отмечено!");
                    return;
                }

                String nextDate = TimezoneSupport
                        .dateInUserZone(result.schedule().getNextDueAt(), user)
                        .format(DATE_FMT);

                answerCallback(
                        client,
                        callbackId,
                        "✅ " + doneVerb(taskType) + ".\nСледующий — " + nextDate
                );

                plantCardService.showPlantCard(
                        user,
                        plantId,
                        messageId,
                        PlantCardService.BACK_TO_LIST,
                        client
                );
            } catch (IllegalArgumentException e) {
                answerCallback(client, callbackId, "❌ " + e.getMessage());
            } catch (RuntimeException e) {
                log.error("Failed to mark care done from card (plant={}, task={}): {}", plantId, taskType, e.getMessage(), e);
                answerCallback(client, callbackId, "❌ Не удалось отметить");
            }

            return;
        }

        if (data.startsWith("PLANT:PHOTO:")) {
            String[] parts = data.substring("PLANT:PHOTO:".length()).split(":");

            Long plantId;

            try {
                plantId = Long.parseLong(parts[0]);
            } catch (NumberFormatException e) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }

            String backTarget = parseBackTarget(parts, 1);
            boolean photoSent = plantCardService.sendPlantPhoto(user, plantId, callbackId, client);

            if (photoSent) {
                plantCardService.showPlantCard(user, plantId, null, backTarget, client);
            }

            return;
        }

        if (data.startsWith("PLANT:HISTORY:")) {
            String[] parts = data.substring("PLANT:HISTORY:".length()).split(":");

            if (parts.length < 2) {
                answerCallback(client, callbackId, "❌ Неверная команда");
                return;
            }

            Long plantId;
            int page;

            try {
                plantId = Long.parseLong(parts[0]);
                page = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                answerCallback(client, callbackId, "❌ Неверная команда");
                return;
            }

            String backTarget = parseBackTarget(parts, 2);

            plantCardService.showHistoryScreen(user, plantId, page, messageId, backTarget, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("PLANT:SPECIES_FACTS:")) {
            String[] parts = data.substring("PLANT:SPECIES_FACTS:".length()).split(":");

            Long plantId;

            try {
                plantId = Long.parseLong(parts[0]);
            } catch (NumberFormatException e) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }

            String backTarget = parseBackTarget(parts, 1);

            plantCardService.showSpeciesFactsScreen(user, plantId, messageId, backTarget, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("PLANT:EVENT:")) {
            handlePlantEventCallback(data, user, messageId, callbackId, client);
            return;
        }

        if (data.startsWith("PLANT:ACCL:")) {
            handlePlantAcclimationCallback(data, user, messageId, callbackId, client);
            return;
        }

        if (data.startsWith("PLANT:SETTINGS:")) {
            String[] parts = data.substring("PLANT:SETTINGS:".length()).split(":");

            Long plantId;

            try {
                plantId = Long.parseLong(parts[0]);
            } catch (NumberFormatException e) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }

            String backTarget = parseBackTarget(parts, 1);

            plantCardService.showSettingsScreen(user, plantId, messageId, backTarget, client);
            answerCallback(client, callbackId, "");
            return;
        }

        // Per-plant сезонность (issue #67): INHERIT → ON → OFF по циклу.
        if (data.startsWith("PLANT:SEASONAL:")) {
            String[] parts = data.substring("PLANT:SEASONAL:".length()).split(":");

            Long plantId;

            try {
                plantId = Long.parseLong(parts[0]);
            } catch (NumberFormatException e) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }

            String backTarget = parseBackTarget(parts, 1);

            plantCardService.cycleSeasonalOverride(user, plantId, messageId, backTarget, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("PLANT:SAVE_TPL:")) {
            String[] parts = data.substring("PLANT:SAVE_TPL:".length()).split(":");

            Long plantId;

            try {
                plantId = Long.parseLong(parts[0]);
            } catch (NumberFormatException e) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }

            if (plantTemplateService.hasReachedLimit(user.getId())) {
                answerCallback(client, callbackId, "❌ Лимит " + PlantTemplateService.MAX_TEMPLATES_PER_USER + " шаблонов");
                return;
            }

            userService.setStateData(user, "save_template_plant_id", String.valueOf(plantId));
            userService.updateState(user, ConversationState.AWAITING_TEMPLATE_NAME);

            try {
                client.execute(SendMessage.builder()
                        .chatId(user.getTelegramChatId().toString())
                        .text("""
                                 *Сохранить как шаблон*

                                Введи название шаблона (до 40 символов) или /cancel.
                                """)
                        .parseMode("Markdown")
                        .build());
            } catch (TelegramApiException e) {
                log.error("Failed to send template name prompt", e);
            }

            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("PLANT:MOVE:")) {
            Long plantId;

            try {
                plantId = Long.parseLong(data.substring("PLANT:MOVE:".length()));
            } catch (NumberFormatException e) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }

            locationMenuService.sendMovePlantDialog(user, plantId, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("PLANT:MOVE_CONFIRM:")) {
            String payload = data.substring("PLANT:MOVE_CONFIRM:".length());
            String[] parts = payload.split(":");

            if (parts.length < 2) {
                answerCallback(client, callbackId, "❌ Неверная команда");
                return;
            }

            Long plantId;
            Long locationId;

            try {
                plantId = Long.parseLong(parts[0]);
                locationId = Long.parseLong(parts[1]);
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                answerCallback(client, callbackId, "❌ Неверная команда");
                return;
            }

            try {
                plantService.movePlantToLocation(user.getId(), plantId, locationId);

                String backTarget = PlantCardService.BACK_TO_LOCATION_PREFIX + locationId;

                plantCardService.showPlantCard(user, plantId, messageId, backTarget, client);
                answerCallback(client, callbackId, "✅ Растение перемещено");
            } catch (IllegalArgumentException e) {
                answerCallback(client, callbackId, "❌ " + e.getMessage());
            }

            return;
        }

        if (data.startsWith("PLANT:EDIT:NAME:")) {
            handleEditStart(
                    data,
                    "PLANT:EDIT:NAME:",
                    ConversationState.AWAITING_PLANT_RENAME,
                    callbackId,
                    messageId,
                    client,
                    user,
                    (u, plantId, msgId, backTarget) -> plantCardService.promptForNewName(u, plantId, msgId, backTarget, client)
            );
            return;
        }

        if (data.startsWith("PLANT:EDIT:NOTE_CLEAR:")) {
            String[] parts = data.substring("PLANT:EDIT:NOTE_CLEAR:".length()).split(":");

            Long plantId;

            try {
                plantId = Long.parseLong(parts[0]);
            } catch (NumberFormatException e) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }

            String backTarget = parseBackTarget(parts, 1);

            try {
                plantService.updateNotes(user.getId(), plantId, null);
            } catch (IllegalArgumentException e) {
                answerCallback(client, callbackId, "❌ " + e.getMessage());
                return;
            }

            plantCardService.showSettingsScreen(user, plantId, null, backTarget, client);
            answerCallback(client, callbackId, "Заметка очищена");
            return;
        }

        if (data.startsWith("PLANT:EDIT:NOTE:")) {
            handleEditStart(
                    data,
                    "PLANT:EDIT:NOTE:",
                    ConversationState.AWAITING_PLANT_NOTE,
                    callbackId,
                    messageId,
                    client,
                    user,
                    (u, plantId, msgId, backTarget) -> plantService
                            .getPlantForUser(u.getId(), plantId)
                            .ifPresent(plant -> plantCardService.promptForNote(u, plant, msgId, backTarget, client))
            );
            return;
        }

        if (data.startsWith("PLANT:EDIT:PHOTO:")) {
            handleEditStart(
                    data,
                    "PLANT:EDIT:PHOTO:",
                    ConversationState.AWAITING_PLANT_PHOTO_EDIT,
                    callbackId,
                    messageId,
                    client,
                    user,
                    (u, plantId, msgId, backTarget) -> plantCardService.promptForPhotoEdit(u, plantId, msgId, backTarget, client)
            );
            return;
        }

        if (data.startsWith("PLANT:EDIT:DELETE_CONFIRM:")) {
            Long plantId;

            try {
                plantId = Long.parseLong(data.substring("PLANT:EDIT:DELETE_CONFIRM:".length()));
            } catch (NumberFormatException e) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }

            try {
                plantService.archivePlant(user.getId(), plantId);
            } catch (IllegalArgumentException e) {
                answerCallback(client, callbackId, "❌ " + e.getMessage());
                return;
            }

            plantMenuService.sendMyPlantsList(user, messageId, client);
            answerCallback(client, callbackId, " Растение удалено");
            return;
        }

        if (data.startsWith("PLANT:EDIT:DELETE:")) {
            String[] parts = data.substring("PLANT:EDIT:DELETE:".length()).split(":");

            Long plantId;

            try {
                plantId = Long.parseLong(parts[0]);
            } catch (NumberFormatException e) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }

            String backTarget = parseBackTarget(parts, 1);

            plantCardService.showDeleteConfirmScreen(user, plantId, messageId, backTarget, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("PLANT:CARE_TYPES:")) {
            String[] parts = data.substring("PLANT:CARE_TYPES:".length()).split(":");

            Long plantId;

            try {
                plantId = Long.parseLong(parts[0]);
            } catch (NumberFormatException e) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }

            String backTarget = parseBackTarget(parts, 1);

            plantCardService.showCareTypesScreen(user, plantId, messageId, backTarget, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("PLANT:SCHED:NEAREST:")) {
            String[] parts = data.substring("PLANT:SCHED:NEAREST:".length()).split(":");

            Long plantId;

            try {
                plantId = Long.parseLong(parts[0]);
            } catch (NumberFormatException e) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }

            String backTarget = parseBackTarget(parts, 1);

            plantCardService.showNearestScheduleScreen(user, plantId, messageId, backTarget, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("PLANT:SCHED:INTERVAL:")) {
            String[] parts = data.substring("PLANT:SCHED:INTERVAL:".length()).split(":");

            if (parts.length < 2) {
                answerCallback(client, callbackId, "❌ Неверная команда");
                return;
            }

            Long plantId;
            TaskType taskType;

            try {
                plantId = Long.parseLong(parts[0]);
                taskType = TaskType.valueOf(parts[1]);
            } catch (IllegalArgumentException e) {
                answerCallback(client, callbackId, "❌ Неверная команда");
                return;
            }

            String backTarget = parseBackTarget(parts, 2);

            userService.updateState(user, ConversationState.AWAITING_NEW_INTERVAL);
            userService.setStateData(user, "edit_plant_id", String.valueOf(plantId));

            if (messageId != null) {
                userService.setStateData(user, "edit_message_id", String.valueOf(messageId));
            }

            userService.setStateData(user, "edit_back_target", backTarget);
            userService.setStateData(user, "edit_task_type", taskType.name());

            plantCardService.promptForNewInterval(user, plantId, taskType, messageId, backTarget, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("PLANT:SCHED:POSTPONE:")) {
            String[] parts = data.substring("PLANT:SCHED:POSTPONE:".length()).split(":");

            if (parts.length < 3) {
                answerCallback(client, callbackId, "❌ Неверная команда");
                return;
            }

            Long plantId;
            TaskType taskType;
            int offsetDays;

            try {
                plantId = Long.parseLong(parts[0]);
                taskType = TaskType.valueOf(parts[1]);
                offsetDays = Integer.parseInt(parts[2]);
            } catch (IllegalArgumentException e) {
                answerCallback(client, callbackId, "❌ Неверная команда");
                return;
            }

            String backTarget = parseBackTarget(parts, 3);

            try {
                LocalDateTime newNext = LocalDateTime.now()
                        .truncatedTo(ChronoUnit.MICROS)
                        .plusDays(offsetDays);

                plantService.rescheduleSchedule(user.getId(), plantId, taskType, newNext);
            } catch (IllegalArgumentException e) {
                answerCallback(client, callbackId, "❌ " + e.getMessage());
                return;
            }

            plantCardService.showScheduleEditByType(user, plantId, taskType, messageId, backTarget, client);
            answerCallback(client, callbackId, "✅ Перенесено");
            return;
        }

        if (data.startsWith("PLANT:SCHED:TOGGLE:")) {
            String[] parts = data.substring("PLANT:SCHED:TOGGLE:".length()).split(":");

            if (parts.length < 2) {
                answerCallback(client, callbackId, "❌ Неверная команда");
                return;
            }

            Long plantId;
            TaskType taskType;

            try {
                plantId = Long.parseLong(parts[0]);
                taskType = TaskType.valueOf(parts[1]);
            } catch (IllegalArgumentException e) {
                answerCallback(client, callbackId, "❌ Неверная команда");
                return;
            }

            String backTarget = parseBackTarget(parts, 2);

            try {
                var saved = plantService.toggleSchedule(user.getId(), plantId, taskType);

                plantCardService.showCareTypesScreen(user, plantId, messageId, backTarget, client);
                answerCallback(client, callbackId, saved.isActive() ? "✅ Включено" : "❌ Выключено");
            } catch (IllegalArgumentException e) {
                answerCallback(client, callbackId, "❌ " + e.getMessage());
            }

            return;
        }

        answerCallback(client, callbackId, "❌ Неизвестная команда");
    }

    private void handleEditStart(
            String data,
            String prefix,
            ConversationState targetState,
            String callbackId,
            Integer messageId,
            TelegramClient client,
            User user,
            EditPromptCallback promptFn
    ) {
        String[] parts = data.substring(prefix.length()).split(":");

        Long plantId;

        try {
            plantId = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            answerCallback(client, callbackId, "❌ Неверный ID");
            return;
        }

        String backTarget = parseBackTarget(parts, 1);

        userService.updateState(user, targetState);
        userService.setStateData(user, "edit_plant_id", String.valueOf(plantId));

        if (messageId != null) {
            userService.setStateData(user, "edit_message_id", String.valueOf(messageId));
        }

        userService.setStateData(user, "edit_back_target", backTarget);

        promptFn.run(user, plantId, messageId, backTarget);
        answerCallback(client, callbackId, "");
    }

    @FunctionalInterface
    private interface EditPromptCallback {
        void run(User user, Long plantId, Integer messageId, String backTarget);
    }

    private void handlePlantEventCallback(
            String data,
            User user,
            Integer messageId,
            String callbackId,
            TelegramClient client
    ) {
        String[] parts = data.substring("PLANT:EVENT:".length()).split(":");

        if (parts.length < 2) {
            answerCallback(client, callbackId, "❌ Неверная команда");
            return;
        }

        String action = parts[0];

        Long plantId;

        try {
            plantId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            answerCallback(client, callbackId, "❌ Неверный ID");
            return;
        }

        switch (action) {
            case "ADD" -> {
                String backTarget = parseBackTarget(parts, 2);
                plantCardService.showEventTypeMenu(user, plantId, messageId, backTarget, client);
                answerCallback(client, callbackId, "");
            }
            case "SAVE" -> {
                if (parts.length < 3) {
                    answerCallback(client, callbackId, "❌ Неверная команда");
                    return;
                }

                PlantEventType type;

                try {
                    type = PlantEventType.valueOf(parts[2]);
                } catch (IllegalArgumentException e) {
                    answerCallback(client, callbackId, "❌ Неизвестный тип события");
                    return;
                }

                String backTarget = parseBackTarget(parts, 3);

                PlantEventService.AddResult result = plantEventService.addEvent(user, plantId, type);

                if (result.wasNotFound()) {
                    answerCallback(client, callbackId, "❌ Растение не найдено");
                    return;
                }

                String label = plantCardService.eventShortLabel(type);
                String alertText = result.wasDuplicate()
                        ? "Уже отмечено!"
                        : "✅ Событие «" + label + "» сохранено в историю";

                answerCallback(client, callbackId, alertText);

                plantCardService.showPlantCard(user, plantId, messageId, backTarget, client);
            }
            case "LIST" -> {
                if (parts.length < 3) {
                    answerCallback(client, callbackId, "❌ Неверная команда");
                    return;
                }

                int page;

                try {
                    page = Integer.parseInt(parts[2]);
                } catch (NumberFormatException e) {
                    answerCallback(client, callbackId, "❌ Неверная команда");
                    return;
                }

                String backTarget = parseBackTarget(parts, 3);

                plantCardService.showEventsScreen(user, plantId, page, messageId, backTarget, client);
                answerCallback(client, callbackId, "");
            }
            default -> answerCallback(client, callbackId, "❌ Неизвестное действие");
        }
    }

    private void handlePlantAcclimationCallback(
            String data,
            User user,
            Integer messageId,
            String callbackId,
            TelegramClient client
    ) {
        String[] parts = data.substring("PLANT:ACCL:".length()).split(":");

        if (parts.length < 2) {
            answerCallback(client, callbackId, "❌ Неверная команда");
            return;
        }

        String action = parts[0];

        Long plantId;

        try {
            plantId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            answerCallback(client, callbackId, "❌ Неверный ID");
            return;
        }

        switch (action) {
            case "YES" -> {
                Plant plant = plantAcclimationService.enable(user, plantId);

                if (plant == null) {
                    answerCallback(client, callbackId, "❌ Растение не найдено");
                    return;
                }

                answerCallback(client, callbackId, " Включил акклиматизацию");
                plantCardService.completePlantCreationAfterAcclimation(user, plant, true, client);
            }
            case "NO" -> {
                Plant plant = plantService
                        .getPlantForUser(user.getId(), plantId)
                        .orElse(null);

                if (plant == null) {
                    answerCallback(client, callbackId, "❌ Растение не найдено");
                    return;
                }

                answerCallback(client, callbackId, "");
                plantCardService.completePlantCreationAfterAcclimation(user, plant, false, client);
            }
            case "DISABLE" -> {
                String backTarget = parseBackTarget(parts, 2);

                Plant plant = plantAcclimationService.disable(user, plantId);

                if (plant == null) {
                    answerCallback(client, callbackId, "❌ Растение не найдено");
                    return;
                }

                answerCallback(client, callbackId, "Ок, дальше работаем в обычном режиме");
                plantCardService.showPlantCard(user, plantId, messageId, backTarget, client);
            }
            default -> answerCallback(client, callbackId, "❌ Неизвестное действие");
        }
    }

    // =================================================================
    // Архив (issue #117)
    // =================================================================

    private void handleArchiveCallback(
            String data,
            String callbackId,
            Integer messageId,
            TelegramClient client,
            User user
    ) {
        if ("ARCHIVE:LIST".equals(data)) {
            plantArchiveMenuService.showArchiveList(user, messageId, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("ARCHIVE:VIEW:")) {
            Long plantId = parseLong(data.substring("ARCHIVE:VIEW:".length()));
            if (plantId == null) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }
            plantArchiveMenuService.showArchiveCard(user, plantId, messageId, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("ARCHIVE:RESTORE:")) {
            Long plantId = parseLong(data.substring("ARCHIVE:RESTORE:".length()));
            if (plantId == null) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }
            try {
                com.plantcare.bot.domain.Plant restored = plantArchiveService.restore(user.getId(), plantId);
                answerCallback(client, callbackId, "♻️ Восстановлено");
                sendText(user, client,
                        restored.getName() + " снова в твоём списке. "
                                + "Не забудь проверить расписания — могло измениться.");
                // Возврат к списку «Мои растения», т.к. растение из архива ушло.
                plantMenuService.sendMyPlantsList(user, messageId, client);
            } catch (jakarta.persistence.EntityNotFoundException e) {
                answerCallback(client, callbackId, "❌ Растение не найдено");
            }
            return;
        }

        if (data.startsWith("ARCHIVE:DELETE_CONFIRM:")) {
            Long plantId = parseLong(data.substring("ARCHIVE:DELETE_CONFIRM:".length()));
            if (plantId == null) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }
            plantArchiveMenuService.showDeleteConfirm(user, plantId, messageId, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("ARCHIVE:DELETE_FINAL:")) {
            Long plantId = parseLong(data.substring("ARCHIVE:DELETE_FINAL:".length()));
            if (plantId == null) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }
            try {
                plantArchiveService.deleteForever(user.getId(), plantId);
                answerCallback(client, callbackId, "🗑 Удалено навсегда");
                plantArchiveMenuService.showArchiveList(user, messageId, client);
            } catch (jakarta.persistence.EntityNotFoundException e) {
                answerCallback(client, callbackId, "❌ Растение не найдено");
            }
            return;
        }

        if (data.startsWith("ARCHIVE:HISTORY:")) {
            // Полная история архива не входит в scope issue #117 — alert-заглушка.
            answerCallback(client, callbackId, "Полная история в разработке");
            return;
        }

        answerCallback(client, callbackId, "❌ Неизвестная команда");
    }

    private String parseBackTarget(String[] parts, int startIndex) {
        if (parts.length > startIndex + 1 && "LOC".equals(parts[startIndex])) {
            return PlantCardService.BACK_TO_LOCATION_PREFIX + parts[startIndex + 1];
        }

        return PlantCardService.BACK_TO_LIST;
    }

    private String doneVerb(TaskType taskType) {
        return switch (taskType) {
            case WATERING -> "Полил";
            case MISTING -> "Опрыскал";
            case FERTILIZING -> "Удобрил";
            case SOIL_CHECK -> "Проверил";
        };
    }

    private LocationPreset getLocationPreset(String key) {
        return switch (key) {
            case "LIVING_ROOM" -> new LocationPreset("Гостиная", "");
            case "BEDROOM" -> new LocationPreset("Спальня", "");
            case "KITCHEN" -> new LocationPreset("Кухня", "");
            case "BALCONY" -> new LocationPreset("Балкон", "");
            case "OFFICE" -> new LocationPreset("Офис", "");
            case "BATHROOM" -> new LocationPreset("Ванная", "");
            default -> throw new IllegalArgumentException("Неизвестный пресет комнаты");
        };
    }

    // =================================================================
    // Отпуск (issue #53)
    // =================================================================

    private static final DateTimeFormatter VACATION_DATE_FMT =
            DateTimeFormatter.ofPattern("dd.MM");

    private void sendVacationDaysMenu(User user, TelegramClient client) {
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("3").callbackData("MENU:VACATION_3").build(),
                        InlineKeyboardButton.builder()
                                .text("7").callbackData("MENU:VACATION_7").build(),
                        InlineKeyboardButton.builder()
                                .text("14").callbackData("MENU:VACATION_14").build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("Другое").callbackData("MENU:VACATION_OTHER").build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("⬅️ Отмена").callbackData("MENU:SETTINGS").build()
                )))
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("🏖 На сколько дней уезжаешь?")
                .replyMarkup(keyboard)
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send vacation days menu", e);
        }
    }

    private void startVacation(User user, TelegramClient client, int days) {
        try {
            User updated = userService.startVacation(user, days);
            String date = formatPausedUntilInUserZone(updated);
            sendText(user, client,
                    "🏖 Отпуск до " + date + ". Бот будет молчать, удачной поездки!");
        } catch (IllegalArgumentException e) {
            sendText(user, client, "❌ " + e.getMessage());
        }
    }

    private void endVacation(User user, TelegramClient client) {
        userService.endVacation(user);
        sendText(user, client, "🌿 С возвращением! Возобновляю напоминания");
    }

    private void extendVacation(User user, TelegramClient client, int days) {
        try {
            User updated = userService.extendVacation(user, days);
            String date = formatPausedUntilInUserZone(updated);
            sendText(user, client, "🏖 Отпуск продлён до " + date);
        } catch (IllegalArgumentException | IllegalStateException e) {
            sendText(user, client, "❌ " + e.getMessage());
        }
    }

    /**
     * Форматирует {@code pausedUntil} (хранится как UTC LocalDateTime) в дату
     * локальной TZ юзера. Если время = null — возвращает «—».
     */
    private String formatPausedUntilInUserZone(User user) {
        LocalDateTime utc = user.getPausedUntil();
        if (utc == null) return "—";
        return TimezoneSupport.dateInUserZone(utc, user).format(VACATION_DATE_FMT);
    }

    private void sendText(User user, TelegramClient client, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(text)
                .build();
        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send text message to {}", user.getTelegramChatId(), e);
        }
    }

    private void sendSettingsMenu(User user, TelegramClient client) {
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("⭐ Мои шаблоны")
                                .callbackData("MENU:MY_TEMPLATES")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("🌐 Таймзона: " + user.getTimezone())
                                .callbackData("MENU:CHANGE_TZ")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("🌙 Тихие часы: "
                                        + user.getQuietHoursStart().format(QUIET_TIME_FMT) + "–"
                                        + user.getQuietHoursEnd().format(QUIET_TIME_FMT))
                                .callbackData("MENU:QUIET_HOURS")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("⛅ Учитывать погоду")
                                .callbackData("MENU:WEATHER")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("🍂 Сезонные интервалы")
                                .callbackData("MENU:SEASONAL")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("🏖 Уехать в отпуск")
                                .callbackData("MENU:VACATION")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("📅 Экспорт в календарь")
                                .callbackData("MENU:CALENDAR_EXPORT")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("⬅️ Назад")
                                .callbackData("MENU:BACK")
                                .build()
                )))
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("""
                        ⚙️ Настройки

                        Текущий часовой пояс: %s

                        Здесь можно изменить регион, чтобы напоминания приходили по местному времени.
                        """.formatted(user.getTimezone()))
                .replyMarkup(keyboard)
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send settings menu", e);
        }
    }

    /**
     * Экран редактирования тихих часов (issue #116).
     */
    private void sendQuietHoursMenu(User user, TelegramClient client) {
        String start = user.getQuietHoursStart().format(QUIET_TIME_FMT);
        String end = user.getQuietHoursEnd().format(QUIET_TIME_FMT);

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("Изменить начало")
                                .callbackData("MENU:QUIET_START")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("Изменить конец")
                                .callbackData("MENU:QUIET_END")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("Сбросить (22:00–09:00)")
                                .callbackData("MENU:QUIET_RESET")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("⬅️ Назад")
                                .callbackData("MENU:SETTINGS")
                                .build()
                )))
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("""
                        🌙 Тихие часы

                        Сейчас: %s–%s

                        В это время бот не присылает напоминания.
                        """.formatted(start, end))
                .replyMarkup(keyboard)
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send quiet hours menu", e);
        }
    }

    /**
     * Issue #79: показывает пользователю публичный URL подписки на .ics календарь.
     * Сначала вне Telegram-вызова получаем/создаём токен (внутри собственной транзакции
     * {@code CalendarExportService.getOrCreateToken}), потом строим URL и шлём сообщение.
     */
    private void sendCalendarExport(User user, TelegramClient client) {
        String token = calendarExportService.getOrCreateToken(user);
        String url = calendarExportService.buildCalendarUrl(token);

        String text = """
                📅 *Экспорт в календарь*

                Скопируй эту ссылку и подпишись на неё в Google/Apple календаре:

                `%s`

                Календарь обновляется автоматически. Задачи на ближайшие 90 дней показаны как события на весь день.
                """.formatted(url);

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("⬅️ Назад в настройки")
                                .callbackData("MENU:SETTINGS")
                                .build()
                )))
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(text)
                .parseMode("Markdown")
                .replyMarkup(keyboard)
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send calendar export menu", e);
        }
    }

    private void sendTimezonePrompt(User user, TelegramClient client) {
        ReplyKeyboardMarkup keyboard = ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow(List.of(
                        KeyboardButton.builder()
                                .text(" Отправить локацию")
                                .requestLocation(true)
                                .build()
                )))
                .keyboardRow(new KeyboardRow(List.of(
                        new KeyboardButton("⌨️ Выбрать вручную")
                )))
                .resizeKeyboard(true)
                .oneTimeKeyboard(true)
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("Выбери, как установить регион для напоминаний:")
                .replyMarkup(keyboard)
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send timezone prompt", e);
        }
    }

    private void sendLocationPresetMenu(User user, TelegramClient client) {
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text(" Гостиная")
                                .callbackData("LOCATION:PRESET:LIVING_ROOM")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text(" Спальня")
                                .callbackData("LOCATION:PRESET:BEDROOM")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text(" Кухня")
                                .callbackData("LOCATION:PRESET:KITCHEN")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text(" Балкон")
                                .callbackData("LOCATION:PRESET:BALCONY")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text(" Офис")
                                .callbackData("LOCATION:PRESET:OFFICE")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text(" Ванная")
                                .callbackData("LOCATION:PRESET:BATHROOM")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("✏️ Своё название")
                                .callbackData("LOCATION:CUSTOM_NAME")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("⬅️ Назад")
                                .callbackData("MENU:LOCATIONS")
                                .build()
                )))
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("Выбери комнату из готовых вариантов или создай свою:")
                .replyMarkup(keyboard)
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send location preset menu", e);
        }
    }

    private InlineKeyboardMarkup buildEmojiKeyboard(String callbackPrefix) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("")
                                .callbackData(callbackPrefix + "")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("")
                                .callbackData(callbackPrefix + "")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("")
                                .callbackData(callbackPrefix + "")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("")
                                .callbackData(callbackPrefix + "")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("")
                                .callbackData(callbackPrefix + "")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("")
                                .callbackData(callbackPrefix + "")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("")
                                .callbackData(callbackPrefix + "")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("❤️")
                                .callbackData(callbackPrefix + "❤️")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("")
                                .callbackData(callbackPrefix + "")
                                .build()
                )))
                .build();
    }

    private InlineKeyboardMarkup buildSpeciesKeyboard(List<Species> species) {
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder<?, ?> builder = InlineKeyboardMarkup.builder();

        for (int i = 0; i < species.size(); i += 2) {
            InlineKeyboardRow row = new InlineKeyboardRow();

            Species first = species.get(i);

            row.add(InlineKeyboardButton.builder()
                    .text(first.getName())
                    .callbackData("SPECIES:" + first.getId())
                    .build());

            if (i + 1 < species.size()) {
                Species second = species.get(i + 1);

                row.add(InlineKeyboardButton.builder()
                        .text(second.getName())
                        .callbackData("SPECIES:" + second.getId())
                        .build());
            }

            builder.keyboardRow(row);
        }

        builder.keyboardRow(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⭐ Из моих шаблонов")
                        .callbackData("SPECIES:MY_TEMPLATES")
                        .build()
        )));

        builder.keyboardRow(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text(" Поиск")
                        .callbackData("SPECIES:SEARCH")
                        .build()
        )));

        builder.keyboardRow(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("✨ Своё без шаблона")
                        .callbackData("SPECIES:CUSTOM")
                        .build()
        )));

        return builder.build();
    }

    private void answerCallback(TelegramClient client, String callbackId, String text) {
        try {
            AnswerCallbackQuery.AnswerCallbackQueryBuilder<?, ?> builder = AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId);

            if (text != null && !text.isBlank()) {
                builder.text(text);
            }

            client.execute(builder.build());
        } catch (TelegramApiException e) {
            log.error("Failed to answer callback: {}", e.getMessage(), e);
        }
    }

    // =================================================================
    // Справочник болезней (issue #140)
    // =================================================================

    /**
     * Маршрутизация callback'ов префикса {@code DISEASE:}.
     *
     * <ul>
     *   <li>{@code DISEASE:LIST} — список всех болезней</li>
     *   <li>{@code DISEASE:SEARCH} — режим поиска по симптому/тексту</li>
     *   <li>{@code DISEASE:VIEW:{id}} — карточка болезни</li>
     * </ul>
     */
    private void handleDiseaseCallback(
            String data,
            String callbackId,
            TelegramClient client,
            User user
    ) {
        if (DiseaseMenuService.LIST_CALLBACK.equals(data)) {
            diseaseMenuService.sendList(user, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (DiseaseMenuService.SEARCH_CALLBACK.equals(data)) {
            diseaseMenuService.startSearch(user, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith(DiseaseMenuService.VIEW_PREFIX)) {
            Long diseaseId = parseLong(data.substring(DiseaseMenuService.VIEW_PREFIX.length()));
            if (diseaseId == null) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }
            diseaseMenuService.sendCard(user, diseaseId, client);
            answerCallback(client, callbackId, "");
            return;
        }

        answerCallback(client, callbackId, "❌ Неизвестная команда");
    }

    // =================================================================
    // Фото-прогресс (issue #72)
    // =================================================================

    /**
     * Маршрутизация callback'ов префикса {@code PHOTO_PROGRESS:}.
     *
     * <p>Поддерживаемые форматы:
     * <ul>
     *   <li>{@code PHOTO_PROGRESS:VIEW:{plantId}} — главный экран фото-прогресса</li>
     *   <li>{@code PHOTO_PROGRESS:FREQ:{plantId}} — экран выбора частоты</li>
     *   <li>{@code PHOTO_PROGRESS:FREQ:SET:{plantId}:{freq}} — установить частоту</li>
     *   <li>{@code PHOTO_PROGRESS:ADD:{plantId}} — перевести в AWAITING_PROGRESS_PHOTO</li>
     *   <li>{@code PHOTO_PROGRESS:SKIP:{plantId}} — пропустить prompt</li>
     *   <li>{@code PHOTO_PROGRESS:CANCEL} — отмена загрузки фото, возврат в IDLE</li>
     *   <li>{@code PHOTO_PROGRESS:HISTORY:{plantId}:{page}} — страница истории</li>
     *   <li>{@code PHOTO_PROGRESS:HISTORY:VIEW:{photoId}} — открыть фото</li>
     *   <li>{@code PHOTO_PROGRESS:COMPARE:{plantId}} — меню сравнения</li>
     *   <li>{@code PHOTO_PROGRESS:COMPARE:FIRST_LAST:{plantId}}</li>
     *   <li>{@code PHOTO_PROGRESS:COMPARE:MONTH_NOW:{plantId}}</li>
     *   <li>{@code PHOTO_PROGRESS:COMPARE:PICK:{plantId}} — выбор пары вручную</li>
     *   <li>{@code PHOTO_PROGRESS:COMPARE:LEFT:{plantId}:{photoId}} — выбран левый</li>
     *   <li>{@code PHOTO_PROGRESS:COMPARE:DO:{plantId}:{leftId}:{rightId}} — сравнение</li>
     * </ul>
     */
    private void handlePhotoProgressCallback(
            String data,
            String callbackId,
            Integer messageId,
            TelegramClient client,
            User user
    ) {
        // Cancel — обрабатываем до парсинга plantId, иначе уйдём в else.
        if ("PHOTO_PROGRESS:CANCEL".equals(data)) {
            Long plantId = readPhotoProgressPlantId(user);
            userService.resetToIdle(user);
            answerCallback(client, callbackId, "");
            if (plantId != null) {
                photoProgressCardService.showPhotoProgressScreen(user, plantId, null, client);
            }
            return;
        }

        if (data.startsWith("PHOTO_PROGRESS:VIEW:")) {
            Long plantId = parseLong(data.substring("PHOTO_PROGRESS:VIEW:".length()));
            if (plantId == null) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }
            photoProgressCardService.showPhotoProgressScreen(user, plantId, messageId, client);
            answerCallback(client, callbackId, "");
            return;
        }

        // SET идёт до FREQ — иначе startsWith("PHOTO_PROGRESS:FREQ:") перехватит.
        if (data.startsWith("PHOTO_PROGRESS:FREQ:SET:")) {
            String[] parts = data.substring("PHOTO_PROGRESS:FREQ:SET:".length()).split(":");
            if (parts.length < 2) {
                answerCallback(client, callbackId, "❌ Неверная команда");
                return;
            }
            Long plantId = parseLong(parts[0]);
            PhotoProgressFrequency freq;
            try {
                freq = PhotoProgressFrequency.valueOf(parts[1]);
            } catch (IllegalArgumentException e) {
                answerCallback(client, callbackId, "❌ Неверный режим");
                return;
            }
            if (plantId == null) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }
            try {
                photoProgressService.setFrequency(user.getId(), plantId, freq);
            } catch (IllegalArgumentException e) {
                answerCallback(client, callbackId, "❌ Растение не найдено");
                return;
            }
            photoProgressCardService.showPhotoProgressScreen(user, plantId, messageId, client);
            answerCallback(client, callbackId, "✅ Режим обновлён");
            return;
        }

        if (data.startsWith("PHOTO_PROGRESS:FREQ:")) {
            Long plantId = parseLong(data.substring("PHOTO_PROGRESS:FREQ:".length()));
            if (plantId == null) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }
            photoProgressCardService.showFrequencyChoice(user, plantId, messageId, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("PHOTO_PROGRESS:ADD:")) {
            Long plantId = parseLong(data.substring("PHOTO_PROGRESS:ADD:".length()));
            if (plantId == null) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }
            // Сохраняем plantId в stateData и переводим в ожидание фото.
            userService.setStateData(user,
                    AwaitingProgressPhotoStateHandler.STATE_KEY_PLANT_ID,
                    String.valueOf(plantId));
            userService.updateState(user, ConversationState.AWAITING_PROGRESS_PHOTO);

            try {
                client.execute(SendMessage.builder()
                        .chatId(user.getTelegramChatId().toString())
                        .text("📸 Пришли фото для таймлайна — оно попадёт в историю фото-прогресса.")
                        .replyMarkup(InlineKeyboardMarkup.builder()
                                .keyboardRow(new InlineKeyboardRow(List.of(
                                        InlineKeyboardButton.builder()
                                                .text("⬅️ Отмена")
                                                .callbackData("PHOTO_PROGRESS:CANCEL")
                                                .build()
                                )))
                                .build())
                        .build());
            } catch (TelegramApiException e) {
                log.error("Failed to send AWAITING_PROGRESS_PHOTO prompt: {}", e.getMessage());
            }
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("PHOTO_PROGRESS:SKIP:")) {
            Long plantId = parseLong(data.substring("PHOTO_PROGRESS:SKIP:".length()));
            if (plantId == null) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }
            try {
                photoProgressService.skipPrompt(user.getId(), plantId);
            } catch (IllegalArgumentException e) {
                answerCallback(client, callbackId, "❌ Растение не найдено");
                return;
            }
            answerCallback(client, callbackId, "⏭ Напомню позже");
            return;
        }

        // HISTORY:VIEW проверяем до HISTORY: — иначе startsWith перехватит.
        if (data.startsWith("PHOTO_PROGRESS:HISTORY:VIEW:")) {
            Long photoId = parseLong(data.substring("PHOTO_PROGRESS:HISTORY:VIEW:".length()));
            if (photoId == null) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }
            photoProgressCardService.showPhoto(user, photoId, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("PHOTO_PROGRESS:HISTORY:")) {
            String[] parts = data.substring("PHOTO_PROGRESS:HISTORY:".length()).split(":");
            if (parts.length < 2) {
                answerCallback(client, callbackId, "❌ Неверная команда");
                return;
            }
            Long plantId = parseLong(parts[0]);
            Integer page = parseInt(parts[1]);
            if (plantId == null || page == null) {
                answerCallback(client, callbackId, "❌ Неверная команда");
                return;
            }
            photoProgressCardService.showHistory(user, plantId, page, messageId, client);
            answerCallback(client, callbackId, "");
            return;
        }

        // COMPARE с подкомандами — проверяем точные префиксы до общего COMPARE:.
        if (data.startsWith("PHOTO_PROGRESS:COMPARE:FIRST_LAST:")) {
            Long plantId = parseLong(data.substring("PHOTO_PROGRESS:COMPARE:FIRST_LAST:".length()));
            if (plantId == null) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }
            photoProgressService.compareFirstVsLast(user.getId(), plantId).ifPresentOrElse(
                    pair -> photoProgressCardService.sendComparison(user, pair, client),
                    () -> answerCallback(client, callbackId, "Нужно минимум 2 фото")
            );
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("PHOTO_PROGRESS:COMPARE:MONTH_NOW:")) {
            Long plantId = parseLong(data.substring("PHOTO_PROGRESS:COMPARE:MONTH_NOW:".length()));
            if (plantId == null) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }
            photoProgressService.compareMonthVsNow(user.getId(), plantId).ifPresentOrElse(
                    pair -> photoProgressCardService.sendComparison(user, pair, client),
                    () -> answerCallback(client, callbackId, "Нет фото месячной давности")
            );
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("PHOTO_PROGRESS:COMPARE:PICK:")) {
            Long plantId = parseLong(data.substring("PHOTO_PROGRESS:COMPARE:PICK:".length()));
            if (plantId == null) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }
            photoProgressCardService.showPickList(user, plantId, null, messageId, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("PHOTO_PROGRESS:COMPARE:LEFT:")) {
            String[] parts = data.substring("PHOTO_PROGRESS:COMPARE:LEFT:".length()).split(":");
            if (parts.length < 2) {
                answerCallback(client, callbackId, "❌ Неверная команда");
                return;
            }
            Long plantId = parseLong(parts[0]);
            Long leftId = parseLong(parts[1]);
            if (plantId == null || leftId == null) {
                answerCallback(client, callbackId, "❌ Неверная команда");
                return;
            }
            photoProgressCardService.showPickList(user, plantId, leftId, messageId, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("PHOTO_PROGRESS:COMPARE:DO:")) {
            String[] parts = data.substring("PHOTO_PROGRESS:COMPARE:DO:".length()).split(":");
            if (parts.length < 3) {
                answerCallback(client, callbackId, "❌ Неверная команда");
                return;
            }
            Long plantId = parseLong(parts[0]);
            Long leftId = parseLong(parts[1]);
            Long rightId = parseLong(parts[2]);
            if (plantId == null || leftId == null || rightId == null) {
                answerCallback(client, callbackId, "❌ Неверная команда");
                return;
            }
            photoProgressService.compareByIds(user.getId(), plantId, leftId, rightId)
                    .ifPresentOrElse(
                            pair -> photoProgressCardService.sendComparison(user, pair, client),
                            () -> answerCallback(client, callbackId, "❌ Не удалось собрать пару")
                    );
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("PHOTO_PROGRESS:COMPARE:")) {
            Long plantId = parseLong(data.substring("PHOTO_PROGRESS:COMPARE:".length()));
            if (plantId == null) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }
            photoProgressCardService.showCompareMenu(user, plantId, messageId, client);
            answerCallback(client, callbackId, "");
            return;
        }

        answerCallback(client, callbackId, "❌ Неизвестная команда");
    }

    private Long readPhotoProgressPlantId(User user) {
        if (user.getStateData() == null) {
            return null;
        }
        Object raw = user.getStateData()
                .get(AwaitingProgressPhotoStateHandler.STATE_KEY_PLANT_ID);
        if (raw == null) {
            return null;
        }
        return parseLong(String.valueOf(raw));
    }

    private Long parseLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInt(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // =================================================================
    // Управление шаблонами растений (issue #68)
    // =================================================================

    private void handleTemplateCallback(
            String data,
            String callbackId,
            TelegramClient client,
            User user
    ) {
        if (data.startsWith("TPL_DELETE_CONFIRM:")) {
            Long templateId;

            try {
                templateId = Long.parseLong(data.substring("TPL_DELETE_CONFIRM:".length()));
            } catch (NumberFormatException e) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }

            try {
                plantTemplateService.deleteTemplate(user, templateId);
                answerCallback(client, callbackId, " Шаблон удалён");
                sendMyTemplatesList(user, client);
            } catch (IllegalArgumentException e) {
                answerCallback(client, callbackId, "❌ " + e.getMessage());
            }

            return;
        }

        if (data.startsWith("TPL_DELETE:")) {
            Long templateId;

            try {
                templateId = Long.parseLong(data.substring("TPL_DELETE:".length()));
            } catch (NumberFormatException e) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }

            sendTemplateDeleteConfirm(user, templateId, client);
            answerCallback(client, callbackId, "");
            return;
        }

        if (data.startsWith("TPL_RENAME:")) {
            Long templateId;

            try {
                templateId = Long.parseLong(data.substring("TPL_RENAME:".length()));
            } catch (NumberFormatException e) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }

            userService.setStateData(user, "rename_template_id", String.valueOf(templateId));
            userService.updateState(user, ConversationState.AWAITING_TEMPLATE_RENAME);

            try {
                client.execute(SendMessage.builder()
                        .chatId(user.getTelegramChatId().toString())
                        .text("✏️ Введи новое название шаблона (до 40 символов) или /cancel.")
                        .build());
            } catch (TelegramApiException e) {
                log.error("Failed to send template rename prompt", e);
            }

            answerCallback(client, callbackId, "");
            return;
        }

        answerCallback(client, callbackId, "❌ Неизвестная команда");
    }

    private void sendMyTemplatesList(User user, TelegramClient client) {
        List<?> templates = plantTemplateService.getUserTemplates(user.getId());
        Long chatId = user.getTelegramChatId();

        if (templates.isEmpty()) {
            try {
                client.execute(SendMessage.builder()
                        .chatId(chatId.toString())
                        .text("""
                                ⭐ *Мои шаблоны*

                                Пока нет шаблонов. Сохрани шаблон из карточки растения.

                                _⚙️ Настройки растения → Сохранить как шаблон_
                                """)
                        .parseMode("Markdown")
                        .replyMarkup(InlineKeyboardMarkup.builder()
                                .keyboardRow(new InlineKeyboardRow(List.of(
                                        InlineKeyboardButton.builder()
                                                .text("⬅️ Назад")
                                                .callbackData("MENU:SETTINGS")
                                                .build()
                                )))
                                .build())
                        .build());
            } catch (TelegramApiException e) {
                log.error("Failed to send empty templates list", e);
            }

            return;
        }

        StringBuilder text = new StringBuilder("⭐ *Мои шаблоны*\n\n");
        List<InlineKeyboardRow> rows = new ArrayList<>();

        for (Object item : templates) {
            PlantTemplate template = (PlantTemplate) item;

            text.append("• ")
                    .append(template.getName())
                    .append(" — ")
                    .append(template.shortDescription())
                    .append("\n");

            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text("✏️ " + template.getName())
                            .callbackData("TPL_RENAME:" + template.getId())
                            .build(),
                    InlineKeyboardButton.builder()
                            .text("🗑")
                            .callbackData("TPL_DELETE:" + template.getId())
                            .build()
            )));
        }

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ Назад")
                        .callbackData("MENU:SETTINGS")
                        .build()
        )));

        try {
            client.execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text.toString())
                    .parseMode("Markdown")
                    .replyMarkup(InlineKeyboardMarkup.builder()
                            .keyboard(rows)
                            .build())
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send templates list", e);
        }
    }

    private void sendTemplateDeleteConfirm(User user, Long templateId, TelegramClient client) {
        try {
            client.execute(SendMessage.builder()
                    .chatId(user.getTelegramChatId().toString())
                    .text("""
                             *Удалить шаблон?*

                            _Растения, созданные из него, останутся без изменений._
                            """)
                    .parseMode("Markdown")
                    .replyMarkup(InlineKeyboardMarkup.builder()
                            .keyboardRow(new InlineKeyboardRow(List.of(
                                    InlineKeyboardButton.builder()
                                            .text("✅ Да, удалить")
                                            .callbackData("TPL_DELETE_CONFIRM:" + templateId)
                                            .build(),
                                    InlineKeyboardButton.builder()
                                            .text("⬅️ Отмена")
                                            .callbackData("MENU:MY_TEMPLATES")
                                            .build()
                            )))
                            .build())
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send delete confirm for template {}", templateId, e);
        }
    }
}
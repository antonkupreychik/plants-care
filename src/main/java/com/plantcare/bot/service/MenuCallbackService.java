package com.plantcare.bot.service;

import com.plantcare.bot.domain.PlantTemplate;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.domain.enums.TaskType;
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
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MenuCallbackService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM");

    private final UserService userService;
    private final PlantService plantService;
    private final LocationMenuService locationMenuService;
    private final LocationService locationService;
    private final MainMenuService mainMenuService;
    private final PlantMenuService plantMenuService;
    private final PlantCardService plantCardService;
    private final CalendarMenuService calendarMenuService;
    private final PlantTemplateService plantTemplateService;

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

        // Шаблоны (issue #68). TPL_RENAME, TPL_DELETE, TPL_DELETE_CONFIRM.
        // TPL_PICK обрабатывается в AwaitingPlantSpeciesChoiceStateHandler.
        if (data.startsWith("TPL_")) {
            handleTemplateCallback(data, callbackId, client, user);
            return;
        }

        // Календарь (issue #52). Формат: cal:week:<offset> где offset — int.
        if (data.startsWith("cal:week:")) {
            handleCalendarWeekCallback(data, callbackId, messageId, client, user);
            return;
        }

        answerCallback(client, callbackId, "❌ Неизвестная команда");
    }

    private void handleCalendarWeekCallback(
            String data, String callbackId, Integer messageId,
            TelegramClient client, User user
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

                var popular = plantService.getPopularSpecies(6);

                SendMessage message = SendMessage.builder()
                        .chatId(chatId.toString())
                        .text("🌿 Давай добавим новое растение!\n\nЧто за растение? Вот популярные виды:")
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

            case "BACK" -> {
                mainMenuService.sendMainMenu(user, client);
                answerCallback(client, callbackId, "");
            }

            case "ALL_PLANTS" -> {
                // Открываем список «Мои растения» новым сообщением: пользователь
                // пришёл сюда из главного меню, у которого свой messageId.
                // Дальнейшая навигация (список ↔ карточка) уже будет EditMessageText
                // по этому новому сообщению.
                plantMenuService.sendMyPlantsList(user, null, client);
                answerCallback(client, callbackId, "");
            }

            case "CALENDAR" -> {
                // Календарь (issue #52). Шлём новым сообщением — листание неделями
                // уже будет EditMessageText по этому сообщению.
                calendarMenuService.sendCalendar(user, client);
                answerCallback(client, callbackId, "");
            }

            case "SETTINGS" -> {
                sendSettingsMenu(user, client);
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
                        .text("✅ Комната удалена. Растений перенесено: " + movedPlantsCount)
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

    private static final java.util.EnumSet<ConversationState> EDIT_MODE_STATES = java.util.EnumSet.of(
            ConversationState.AWAITING_PLANT_RENAME,
            ConversationState.AWAITING_PLANT_NOTE,
            ConversationState.AWAITING_PLANT_PHOTO_EDIT,
            ConversationState.AWAITING_NEW_INTERVAL
    );

    private void handlePlantCallback(
            String data,
            String callbackId,
            Integer messageId,
            TelegramClient client,
            User user
    ) {
        // Любой callback на «PLANT:*» во время activе edit-режима означает выход из него
        // (либо в Cancel, либо в новый edit, либо в другую часть навигации).
        // resetToIdle очищает stateData; если callback ниже запускает новый edit,
        // он повторно положит туда контекст.
        if (EDIT_MODE_STATES.contains(user.getConversationState())) {
            userService.resetToIdle(user);
        }

        // Возврат к списку «Мои растения» из карточки — редактируем то же сообщение.
        if ("PLANT:LIST".equals(data)) {
            plantMenuService.sendMyPlantsList(user, messageId, client);
            answerCallback(client, callbackId, "");
            return;
        }

        // Открыть карточку растения.
        // Формат: PLANT:VIEW:<id>             — назад в список
        //         PLANT:VIEW:<id>:LOC:<locId> — назад в комнату
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

        // Быстрая отметка ухода: PLANT:CARE:<plantId>:<TaskType>
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
                PlantService.MarkCareDoneResult result =
                        plantService.markCareDone(user.getId(), plantId, taskType);

                if (result == null) {
                    answerCallback(client, callbackId, "❌ Расписание не настроено");
                    return;
                }

                if (result.wasDuplicate()) {
                    answerCallback(client, callbackId, "Уже отмечено!");
                    return;
                }

                String nextDate = result.schedule().getNextDueAt().toLocalDate().format(DATE_FMT);
                answerCallback(
                        client,
                        callbackId,
                        "✅ " + doneVerb(taskType) + ". Следующий — " + nextDate
                );

                // Перерисовываем карточку — даты сдвинулись.
                // back-контекст после care не сохраняем: пользователь уже внутри карточки;
                // если важно, можно прокинуть через stateData, но это усложнение
                // ради пограничного UX.
                plantCardService.showPlantCard(
                        user, plantId, messageId, PlantCardService.BACK_TO_LIST, client
                );
            } catch (IllegalArgumentException e) {
                answerCallback(client, callbackId, "❌ " + e.getMessage());
            } catch (RuntimeException e) {
                log.error("Failed to mark care done from card (plant={}, task={}): {}",
                        plantId, taskType, e.getMessage(), e);
                answerCallback(client, callbackId, "❌ Не удалось отметить");
            }
            return;
        }

        // Просмотр фото отдельным сообщением. После успешной отправки фото
        // дублируем карточку растения новым сообщением вниз чата, чтобы юзеру
        // не пришлось скроллить наверх для следующих действий (отметить уход,
        // открыть настройки и т.д.).
        // Формат: PLANT:PHOTO:<id>[:LOC:<locId>]
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
                // messageId=null → карточка приходит новым сообщением, а не правит
                // старое (то самое, по кнопке которого юзер кликнул) — оно так и
                // останется выше в истории.
                plantCardService.showPlantCard(user, plantId, null, backTarget, client);
            }
            return;
        }

        // История ухода с пагинацией: PLANT:HISTORY:<id>:<page>[:LOC:<locId>]
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

        // Настройки: PLANT:SETTINGS:<id>[:LOC:<locId>]
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

        // issue #68: сохранение растения как шаблон
        // Формат: PLANT:SAVE_TPL:<plantId>[:LOC:<locId>]
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
                answerCallback(client, callbackId,
                        "❌ Лимит " + PlantTemplateService.MAX_TEMPLATES_PER_USER + " шаблонов");
                return;
            }

            userService.setStateData(user, "save_template_plant_id", String.valueOf(plantId));
            userService.updateState(user, ConversationState.AWAITING_TEMPLATE_NAME);

            try {
                client.execute(SendMessage.builder()
                        .chatId(user.getTelegramChatId().toString())
                        .text("💾 *Сохранить как шаблон*\n\n" +
                              "Введи название шаблона (до 40 символов) или /cancel.")
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
            } catch (NumberFormatException e) {
                answerCallback(client, callbackId, "❌ Неверная команда");
                return;
            }

            try {
                plantId = Long.parseLong(parts[0]);
                locationId = Long.parseLong(parts[1]);
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                answerCallback(client, callbackId, "❌ Неверная команда");
                return;
            }

            try {
                plantService.movePlantToLocation(user.getId(), plantId, locationId);

                // Перенесённое растение лежит в новой комнате — back-таргет
                // обновляем на её id, чтобы кнопка «К комнате» вела куда надо.
                String backTarget = PlantCardService.BACK_TO_LOCATION_PREFIX + locationId;
                plantCardService.showPlantCard(user, plantId, messageId, backTarget, client);
                answerCallback(client, callbackId, "✅ Растение перемещено");

            } catch (IllegalArgumentException e) {
                answerCallback(client, callbackId, "❌ " + e.getMessage());
            }
            return;
        }

        // ============== Edit mode (issue #27) ==============

        // Старт текстовых/фото-сценариев редактирования.
        if (data.startsWith("PLANT:EDIT:NAME:")) {
            handleEditStart(data, "PLANT:EDIT:NAME:",
                    ConversationState.AWAITING_PLANT_RENAME,
                    callbackId, messageId, client, user,
                    (u, plantId, msgId, backTarget) ->
                            plantCardService.promptForNewName(u, plantId, msgId, backTarget, client));
            return;
        }

        if (data.startsWith("PLANT:EDIT:NOTE_CLEAR:")) {
            // Очистка без перехода в state — сразу применяем и возвращаем экран настроек.
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
            // Сообщение с промптом и кнопкой «очистить» больше не нужно — просто пересоберём
            // экран настроек новым сообщением (messageId сообщения-промпта тут).
            plantCardService.showSettingsScreen(user, plantId, null, backTarget, client);
            answerCallback(client, callbackId, "Заметка очищена");
            return;
        }

        if (data.startsWith("PLANT:EDIT:NOTE:")) {
            handleEditStart(data, "PLANT:EDIT:NOTE:",
                    ConversationState.AWAITING_PLANT_NOTE,
                    callbackId, messageId, client, user,
                    (u, plantId, msgId, backTarget) -> {
                        // Достаём растение, чтобы показать текущую заметку в промпте.
                        plantService.getPlantForUser(u.getId(), plantId).ifPresent(plant ->
                                plantCardService.promptForNote(u, plant, msgId, backTarget, client)
                        );
                    });
            return;
        }

        if (data.startsWith("PLANT:EDIT:PHOTO:")) {
            handleEditStart(data, "PLANT:EDIT:PHOTO:",
                    ConversationState.AWAITING_PLANT_PHOTO_EDIT,
                    callbackId, messageId, client, user,
                    (u, plantId, msgId, backTarget) ->
                            plantCardService.promptForPhotoEdit(u, plantId, msgId, backTarget, client));
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
            // После архивирования карточки больше нет — возвращаем список «Мои растения»
            // в том же сообщении.
            plantMenuService.sendMyPlantsList(user, messageId, client);
            answerCallback(client, callbackId, "🗑 Растение удалено");
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
            // PLANT:SCHED:INTERVAL:<id>:<type>[:LOC:<locId>]
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

            // Регистрируем edit-контекст и переключаемся в AWAITING_NEW_INTERVAL.
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
            // PLANT:SCHED:POSTPONE:<id>:<type>:<offsetDays>[:LOC:<locId>]
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
            // PLANT:SCHED:TOGGLE:<id>:<type>[:LOC:<locId>]
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
                answerCallback(client, callbackId,
                        saved.isActive() ? "✅ Включено" : "❌ Выключено");
            } catch (IllegalArgumentException e) {
                answerCallback(client, callbackId, "❌ " + e.getMessage());
            }
            return;
        }

        answerCallback(client, callbackId, "❌ Неизвестная команда");
    }

    /**
     * Универсальный «старт» сценария редактирования с текстовым/фото-вводом:
     *   1) парсим plantId и back-target из callback-data вида PLANT:EDIT:XXX:<id>[:LOC:<locId>]
     *   2) кладём контекст в stateData
     *   3) переводим юзера в нужное state
     *   4) показываем кастомный промпт (см. promptFn)
     */
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

    /**
     * Парсит back-target из остатка callback-data. Поддерживает формат «LOC:<id>».
     * Возвращает {@link PlantCardService#BACK_TO_LIST} по умолчанию.
     */
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
        };
    }

    private LocationPreset getLocationPreset(String key) {
        return switch (key) {
            case "LIVING_ROOM" -> new LocationPreset("Гостиная", "🛋");
            case "BEDROOM" -> new LocationPreset("Спальня", "🛏");
            case "KITCHEN" -> new LocationPreset("Кухня", "🍳");
            case "BALCONY" -> new LocationPreset("Балкон", "🌿");
            case "OFFICE" -> new LocationPreset("Офис", "💼");
            case "BATHROOM" -> new LocationPreset("Ванная", "🚿");
            default -> throw new IllegalArgumentException("Неизвестный пресет комнаты");
        };
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
                                .text("🌍 Изменить регион")
                                .callbackData("MENU:CHANGE_TZ")
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

    private void sendTimezonePrompt(User user, TelegramClient client) {
        ReplyKeyboardMarkup keyboard = ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow(List.of(
                        KeyboardButton.builder()
                                .text("📍 Отправить локацию")
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
                                .text("🛋 Гостиная")
                                .callbackData("LOCATION:PRESET:LIVING_ROOM")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("🛏 Спальня")
                                .callbackData("LOCATION:PRESET:BEDROOM")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("🍳 Кухня")
                                .callbackData("LOCATION:PRESET:KITCHEN")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("🌿 Балкон")
                                .callbackData("LOCATION:PRESET:BALCONY")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("💼 Офис")
                                .callbackData("LOCATION:PRESET:OFFICE")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("🚿 Ванная")
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
                                .text("🛋")
                                .callbackData(callbackPrefix + "🛋")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("🛏")
                                .callbackData(callbackPrefix + "🛏")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("🍳")
                                .callbackData(callbackPrefix + "🍳")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("🌿")
                                .callbackData(callbackPrefix + "🌿")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("💼")
                                .callbackData(callbackPrefix + "💼")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("🚿")
                                .callbackData(callbackPrefix + "🚿")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("🪴")
                                .callbackData(callbackPrefix + "🪴")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("❤️")
                                .callbackData(callbackPrefix + "❤️")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("🌱")
                                .callbackData(callbackPrefix + "🌱")
                                .build()
                )))
                .build();
    }

    private InlineKeyboardMarkup buildSpeciesKeyboard(
            List<com.plantcare.bot.domain.Species> species
    ) {
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder builder = InlineKeyboardMarkup.builder();

        for (int i = 0; i < species.size(); i += 2) {
            InlineKeyboardRow row = new InlineKeyboardRow();

            var first = species.get(i);

            row.add(InlineKeyboardButton.builder()
                    .text(first.getName())
                    .callbackData("SPECIES:" + first.getId())
                    .build());

            if (i + 1 < species.size()) {
                var second = species.get(i + 1);

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
                        .text("🔍 Поиск")
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
            AnswerCallbackQuery.AnswerCallbackQueryBuilder builder = AnswerCallbackQuery.builder()
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
    // Управление шаблонами растений (issue #68)
    // =================================================================

    private void handleTemplateCallback(
            String data,
            String callbackId,
            TelegramClient client,
            User user
    ) {
        // ВАЖНО: TPL_DELETE_CONFIRM проверяется ДО TPL_DELETE,
        // иначе startsWith("TPL_DELETE:") перехватит подтверждение.
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
                answerCallback(client, callbackId, "🗑 Шаблон удалён");
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
        java.util.List<PlantTemplate> templates =
                plantTemplateService.getUserTemplates(user.getId());
        Long chatId = user.getTelegramChatId();

        if (templates.isEmpty()) {
            try {
                client.execute(SendMessage.builder()
                        .chatId(chatId.toString())
                        .text("⭐ *Мои шаблоны*\n\n" +
                              "Пока нет шаблонов. Сохрани шаблон из карточки растения.\n\n" +
                              "_⚙️ Настройки растения → 💾 Сохранить как шаблон_")
                        .parseMode("Markdown")
                        .replyMarkup(InlineKeyboardMarkup.builder()
                                .keyboardRow(new InlineKeyboardRow(java.util.List.of(
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
        java.util.List<InlineKeyboardRow> rows = new java.util.ArrayList<>();

        for (PlantTemplate template : templates) {
            text.append("• ").append(template.getName())
                .append(" — ").append(template.shortDescription()).append("\n");
            rows.add(new InlineKeyboardRow(java.util.List.of(
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
        rows.add(new InlineKeyboardRow(java.util.List.of(
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
                    .replyMarkup(InlineKeyboardMarkup.builder().keyboard(rows).build())
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send templates list", e);
        }
    }

    private void sendTemplateDeleteConfirm(User user, Long templateId, TelegramClient client) {
        try {
            client.execute(SendMessage.builder()
                    .chatId(user.getTelegramChatId().toString())
                    .text("🗑 *Удалить шаблон?*\n\n" +
                          "_Растения, созданные из него, останутся без изменений._")
                    .parseMode("Markdown")
                    .replyMarkup(InlineKeyboardMarkup.builder()
                            .keyboardRow(new InlineKeyboardRow(java.util.List.of(
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

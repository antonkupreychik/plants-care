package com.plantcare.bot.service;

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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.format.DateTimeFormatter;
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

        answerCallback(client, callbackId, "❌ Неизвестная команда");
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

            case "SETTINGS" -> {
                answerCallback(client, callbackId, "🚧 Скоро будет доступно!");
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

    private void handlePlantCallback(
            String data,
            String callbackId,
            Integer messageId,
            TelegramClient client,
            User user
    ) {
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

        // Просмотр фото отдельным сообщением.
        if (data.startsWith("PLANT:PHOTO:")) {
            Long plantId;
            try {
                plantId = Long.parseLong(data.substring("PLANT:PHOTO:".length()));
            } catch (NumberFormatException e) {
                answerCallback(client, callbackId, "❌ Неверный ID");
                return;
            }

            plantCardService.sendPlantPhoto(user, plantId, callbackId, client);
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

        answerCallback(client, callbackId, "❌ Неизвестная команда");
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
}
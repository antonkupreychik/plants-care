package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.service.PlantService;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Шаг создания растения: настройка опрыскивания (MISTING).
 *
 * Сценарии:
 * - MISTING:DEFAULT  → создаём расписание с дефолтным интервалом 3 дня
 * - MISTING:CUSTOM   → просим ввести интервал текстом
 * - MISTING:SKIP     → пропускаем, идём к удобрению
 * - текст с числом   → обрабатываем введённый интервал (после CUSTOM)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingPlantMistingSetupStateHandler implements StateHandler {

    private static final int DEFAULT_MISTING_DAYS = 3;

    private final UserService userService;
    private final PlantService plantService;
    private final PlantRepository plantRepository;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_PLANT_MISTING_SETUP;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        // Ввод числа — пользователь задаёт кастомный интервал
        if (update.hasMessage() && update.getMessage().hasText()) {
            handleCustomIntervalInput(user, update, client);
            return;
        }

        if (!update.hasCallbackQuery()) {
            return;
        }

        String callbackData = update.getCallbackQuery().getData();
        answerCallback(client, update.getCallbackQuery().getId());

        switch (callbackData) {
            case "MISTING:DEFAULT" -> {
                createMistingSchedule(user, DEFAULT_MISTING_DAYS, client);
                proceedToFertilizing(user, client);
            }
            case "MISTING:CUSTOM" -> askForCustomInterval(user, client);
            case "MISTING:SKIP"   -> {
                log.info("User {} skipped misting for plant {}", user.getTelegramChatId(),
                        getPlantId(user));
                proceedToFertilizing(user, client);
            }
            default -> log.warn("Unknown misting callback: {}", callbackData);
        }
    }

    private void handleCustomIntervalInput(User user, Update update, TelegramClient client) {
        String text = update.getMessage().getText().trim();
        Long chatId = user.getTelegramChatId();

        // Проверяем что мы в режиме ожидания ввода (флаг в stateData)
        Map<String, Object> stateData = user.getStateData();
        if (!"true".equals(stateData.get("misting_awaiting_input"))) {
            return;
        }

        try {
            int interval = Integer.parseInt(text);
            if (!PlantService.isValidInterval(interval)) {
                sendText(client, chatId, "❌ Интервал должен быть от 1 до 365 дней. Попробуй ещё раз:");
                return;
            }

            userService.setStateData(user, "misting_awaiting_input", null);
            createMistingSchedule(user, interval, client);
            proceedToFertilizing(user, client);

        } catch (NumberFormatException e) {
            sendText(client, chatId, "❌ Введи число от 1 до 365, пожалуйста.");
        }
    }

    private void askForCustomInterval(User user, TelegramClient client) {
        userService.setStateData(user, "misting_awaiting_input", "true");
        sendText(client, user.getTelegramChatId(),
                "📅 Введи интервал опрыскивания в днях (от 1 до 365).\n\nНапример: 2 — через день");
    }

    private void createMistingSchedule(User user, int intervalDays, TelegramClient client) {
        Long plantId = getPlantId(user);
        Plant plant = plantRepository.findById(plantId).orElse(null);
        if (plant == null) {
            log.error("Plant {} not found for user {} during misting setup",
                    plantId, user.getTelegramChatId());
            return;
        }

        LocalDateTime nextDueAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)
                .plusDays(intervalDays);
        plantService.addCareSchedule(plant, TaskType.MISTING, intervalDays, nextDueAt);

        log.info("Misting schedule created: plant={}, interval={}", plantId, intervalDays);
    }

    private void proceedToFertilizing(User user, TelegramClient client) {
        Long plantId = getPlantId(user);
        Plant plant = plantRepository.findById(plantId).orElse(null);
        String plantName = plant != null ? plant.getName() : "растение";

        userService.updateState(user, ConversationState.AWAITING_PLANT_FERTILIZING_SETUP);

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("🌿 Да, каждые 14 дней")
                                .callbackData("FERTILIZING:DEFAULT")
                                .build()
                ))
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("✏️ Да, задать интервал")
                                .callbackData("FERTILIZING:CUSTOM")
                                .build()
                ))
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("❌ Не нужно")
                                .callbackData("FERTILIZING:SKIP")
                                .build()
                ))
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("🌿 Нужно ли удобрять *" + plantName + "*?\n\n"
                        + "Удобрение даёт растению питательные вещества для роста.")
                .parseMode("Markdown")
                .replyMarkup(keyboard)
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send fertilizing question", e);
        }
    }

    private Long getPlantId(User user) {
        Object raw = user.getStateData().get("plant_id");
        if (raw == null) throw new IllegalStateException("plant_id missing from stateData");
        return Long.parseLong(raw.toString());
    }

    private void sendText(TelegramClient client, Long chatId, String text) {
        try {
            client.execute(SendMessage.builder().chatId(chatId.toString()).text(text).build());
        } catch (TelegramApiException e) {
            log.error("Failed to send message", e);
        }
    }

    private void answerCallback(TelegramClient client, String callbackId) {
        try {
            client.execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId).build());
        } catch (Exception e) {
            log.error("Failed to answer callback", e);
        }
    }
}
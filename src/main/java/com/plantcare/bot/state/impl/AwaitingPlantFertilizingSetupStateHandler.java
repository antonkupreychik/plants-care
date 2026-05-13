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
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingPlantFertilizingSetupStateHandler implements StateHandler {

    private static final int DEFAULT_FERTILIZING_INTERVAL_DAYS = 14;
    private static final String FERTILIZING_AWAITING_INPUT_KEY = "fertilizing_awaiting_input";
    private static final String PLANT_ID_KEY = "plant_id";

    private final UserService userService;
    private final PlantService plantService;
    private final PlantRepository plantRepository;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_PLANT_FERTILIZING_SETUP;
    }

    @Override
    @Transactional
    public void handle(User user, Update update, TelegramClient client) {
        Map<String, Object> stateData = user.getStateData();

        boolean awaitingCustomInput = "true".equals(
                String.valueOf(stateData.get(FERTILIZING_AWAITING_INPUT_KEY))
        );

        if (awaitingCustomInput && update.hasMessage() && update.getMessage().hasText()) {
            handleCustomIntervalInput(user, update.getMessage().getText(), client);
            return;
        }

        if (!update.hasCallbackQuery()) {
            sendText(
                    client,
                    user.getTelegramChatId(),
                    "Пожалуйста, выбери вариант кнопкой ниже или введи интервал, если выбрал ручную настройку."
            );
            return;
        }

        String callbackData = update.getCallbackQuery().getData();

        if (callbackData == null || !callbackData.startsWith("FERTILIZING:")) {
            return;
        }

        answerCallback(update, client);

        String action = callbackData.substring("FERTILIZING:".length());

        switch (action) {
            case "DEFAULT" -> handleDefault(user, client);
            case "CUSTOM" -> handleCustomRequest(user, client);
            case "SKIP" -> handleSkip(user, client);
            default -> sendText(client, user.getTelegramChatId(), "❌ Неизвестный вариант удобрения.");
        }
    }

    private void handleDefault(User user, TelegramClient client) {
        Plant plant = findPlantFromStateData(user, client);

        if (plant == null) {
            return;
        }

        LocalDateTime nextDueAt = LocalDateTime.now()
                .truncatedTo(ChronoUnit.MICROS)
                .plusDays(DEFAULT_FERTILIZING_INTERVAL_DAYS);

        plantService.addCareSchedule(
                plant,
                TaskType.FERTILIZING,
                DEFAULT_FERTILIZING_INTERVAL_DAYS,
                nextDueAt
        );

        log.info(
                "Fertilizing schedule created: plant={}, interval={}",
                plant.getId(),
                DEFAULT_FERTILIZING_INTERVAL_DAYS
        );

        proceedToPhotoStep(user, plant, client);
    }

    private void handleCustomRequest(User user, TelegramClient client) {
        userService.setStateData(user, FERTILIZING_AWAITING_INPUT_KEY, "true");

        sendText(
                client,
                user.getTelegramChatId(),
                """
                ✏️ Введи интервал удобрения в днях.

                Например:
                14
                21
                30
                """
        );
    }

    private void handleCustomIntervalInput(User user, String text, TelegramClient client) {
        Long chatId = user.getTelegramChatId();

        int intervalDays;

        try {
            intervalDays = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            sendText(client, chatId, "❌ Введи число от 1 до 365.");
            return;
        }

        if (!PlantService.isValidInterval(intervalDays)) {
            sendText(client, chatId, "❌ Интервал должен быть от 1 до 365 дней.");
            return;
        }

        Plant plant = findPlantFromStateData(user, client);

        if (plant == null) {
            return;
        }

        userService.removeStateData(user, FERTILIZING_AWAITING_INPUT_KEY);

        LocalDateTime nextDueAt = LocalDateTime.now()
                .truncatedTo(ChronoUnit.MICROS)
                .plusDays(intervalDays);

        plantService.addCareSchedule(
                plant,
                TaskType.FERTILIZING,
                intervalDays,
                nextDueAt
        );

        log.info(
                "Custom fertilizing schedule created: plant={}, interval={}",
                plant.getId(),
                intervalDays
        );

        proceedToPhotoStep(user, plant, client);
    }

    private void handleSkip(User user, TelegramClient client) {
        Plant plant = findPlantFromStateData(user, client);

        if (plant == null) {
            return;
        }

        log.info(
                "User {} skipped fertilizing for plant {}",
                user.getTelegramChatId(),
                plant.getId()
        );

        proceedToPhotoStep(user, plant, client);
    }

    private Plant findPlantFromStateData(User user, TelegramClient client) {
        Object plantIdValue = user.getStateData().get(PLANT_ID_KEY);

        if (plantIdValue == null) {
            log.error("plant_id not found in stateData for user {}", user.getTelegramChatId());
            sendText(client, user.getTelegramChatId(), "❌ Не удалось найти растение. Попробуй добавить его заново.");
            userService.resetToIdle(user);
            return null;
        }

        Long plantId;

        try {
            plantId = Long.parseLong(String.valueOf(plantIdValue));
        } catch (NumberFormatException e) {
            log.error("Invalid plant_id={} for user {}", plantIdValue, user.getTelegramChatId(), e);
            sendText(client, user.getTelegramChatId(), "❌ Некорректные данные растения. Попробуй добавить его заново.");
            userService.resetToIdle(user);
            return null;
        }

        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .orElse(null);

        if (plant == null) {
            log.error("Plant {} not found during fertilizing setup for user {}", plantId, user.getTelegramChatId());
            sendText(client, user.getTelegramChatId(), "❌ Растение не найдено. Попробуй добавить его заново.");
            userService.resetToIdle(user);
            return null;
        }

        return plant;
    }

    /**
     * Переход к шагу загрузки фото.
     * Финализация создания (карточка + reset state + главное меню) выполняется уже в
     * {@link AwaitingPlantPhotoStateHandler}.
     */
    private void proceedToPhotoStep(User user, Plant plant, TelegramClient client) {
        userService.updateState(user, ConversationState.AWAITING_PLANT_PHOTO);

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("⏭ Пропустить")
                                .callbackData("PHOTO:SKIP")
                                .build()
                ))
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("📸 Пришли фото *" + plant.getName() + "* — оно будет показываться "
                        + "в карточке растения.\n\n"
                        + "Можно пропустить — добавишь позже из карточки растения.")
                .parseMode("Markdown")
                .replyMarkup(keyboard)
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send photo upload prompt", e);
        }
    }

    private void sendText(TelegramClient client, Long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .build();

        execute(client, message);
    }

    private void execute(TelegramClient client, SendMessage message) {
        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send fertilizing setup message", e);
        }
    }

    private void answerCallback(Update update, TelegramClient client) {
        try {
            client.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(update.getCallbackQuery().getId())
                    .build());
        } catch (Exception e) {
            log.error("Failed to answer callback", e);
        }
    }
}

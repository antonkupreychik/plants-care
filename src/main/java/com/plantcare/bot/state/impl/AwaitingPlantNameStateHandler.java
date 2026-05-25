package com.plantcare.bot.state.impl;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.UserService;
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

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingPlantNameStateHandler implements StateHandler {

    private final UserService userService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_PLANT_NAME;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        Long chatId = user.getTelegramChatId();

        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String plantName = update.getMessage().getText().trim();

        // Валидируем имя
        if (!PlantService.isValidPlantName(plantName)) {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text("❌ Имя должно быть от 1 до 100 символов, не пустое.\n\n" +
                            "Попробуй ещё раз:")
                    .build();

            try {
                client.execute(message);
            } catch (TelegramApiException e) {
                log.error("Failed to send validation error", e);
            }
            return;
        }

        log.info("User {} named plant '{}'", chatId, plantName);
        userService.setStateData(user, "plant_name", plantName);

        // issue #117: между именем и расписанием — шаг «когда завёл растение?».
        // Пользователь выбирает один из пресетов кнопкой или вводит дату вручную.
        userService.updateState(user, ConversationState.AWAITING_PLANT_ACQUIRED_CHOICE);

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text("🌱 Когда ты завёл " + plantName + "? Можно пропустить.")
                .replyMarkup(buildAcquiredChoiceKeyboard())
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send acquired-date question", e);
        }
    }

    private InlineKeyboardMarkup buildAcquiredChoiceKeyboard() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("Сегодня")
                                .callbackData("ACQUIRED:TODAY")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("На этой неделе")
                                .callbackData("ACQUIRED:WEEK")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("Месяц назад")
                                .callbackData("ACQUIRED:MONTH")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("Давно (укажу)")
                                .callbackData("ACQUIRED:MANUAL")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("Пропустить")
                                .callbackData("ACQUIRED:SKIP")
                                .build()
                )))
                .build();
    }
}
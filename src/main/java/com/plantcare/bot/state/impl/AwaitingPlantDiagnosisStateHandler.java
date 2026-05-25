package com.plantcare.bot.state.impl;

import com.plantcare.bot.diagnosis.PlantDiagnosisService;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.bot.state.interfaces.StateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingPlantDiagnosisStateHandler implements StateHandler {

    private final PlantDiagnosisService plantDiagnosisService;

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        Message message = extractMessage(update);

        if (message != null && plantDiagnosisService.handlePhotoIfWaiting(message, client, user)) {
            return;
        }

        sendMessage(
                user.getTelegramChatId(),
                """
                Сейчас идёт диагностика растения.

                Используй кнопки под сообщением диагностики.
                Если выбрал «📸 Сделать фото» — отправь фото растения одним сообщением.
                Для отмены нажми «❌ Отмена».
                """,
                client
        );
    }

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_PLANT_DIAGNOSIS;
    }

    private Message extractMessage(Update update) {
        if (update == null) {
            return null;
        }

        if (update.hasMessage()) {
            return update.getMessage();
        }

        if (update.hasEditedMessage()) {
            return update.getEditedMessage();
        }

        return null;
    }

    private void sendMessage(Long chatId, String text, TelegramClient client) {
        try {
            client.execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send diagnosis state fallback message", e);
        }
    }
}
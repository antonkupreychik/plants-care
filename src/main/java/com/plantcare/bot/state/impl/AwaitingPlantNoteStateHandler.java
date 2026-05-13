package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.PlantCardService;
import com.plantcare.bot.service.PlantService;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Map;

/**
 * Шаг редактирования: установка/изменение заметки к растению (issue #27).
 *
 * Очистка заметки делается отдельной кнопкой PLANT:EDIT:NOTE_CLEAR в
 * MenuCallbackService — здесь только сохранение нового текста.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingPlantNoteStateHandler implements StateHandler {

    private final UserService userService;
    private final PlantService plantService;
    private final PlantCardService plantCardService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_PLANT_NOTE;
    }

    @Override
    @Transactional
    public void handle(User user, Update update, TelegramClient client) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            sendHint(client, user.getTelegramChatId(),
                    "📝 Пришли текст заметки или нажми «Отмена» / «Очистить».");
            return;
        }

        String note = update.getMessage().getText().trim();
        if (note.length() > PlantService.NOTE_MAX_LENGTH) {
            sendHint(client, user.getTelegramChatId(),
                    "❌ Заметка слишком длинная (макс "
                            + PlantService.NOTE_MAX_LENGTH + " символов).");
            return;
        }

        Map<String, Object> stateData = user.getStateData();
        Long plantId = EditStateData.plantId(stateData);
        Integer messageId = EditStateData.messageId(stateData);
        String backTarget = EditStateData.backTarget(stateData);

        if (plantId == null) {
            log.error("edit_plant_id missing for user {} in note flow",
                    user.getTelegramChatId());
            userService.resetToIdle(user);
            sendHint(client, user.getTelegramChatId(),
                    "❌ Контекст редактирования утерян. Открой карточку и попробуй снова.");
            return;
        }

        try {
            plantService.updateNotes(user.getId(), plantId, note);
        } catch (IllegalArgumentException e) {
            sendHint(client, user.getTelegramChatId(), "❌ " + e.getMessage());
            return;
        }

        userService.resetToIdle(user);
        plantCardService.showSettingsScreen(user, plantId, messageId, backTarget, client);
    }

    private void sendHint(TelegramClient client, Long chatId, String text) {
        try {
            client.execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send note hint", e);
        }
    }
}

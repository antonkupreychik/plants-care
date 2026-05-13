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
 * Шаг редактирования: переименование растения (issue #27).
 * Stateflow:
 *   1) Юзер из настроек жмёт «✏️ Переименовать» →
 *      в stateData кладётся edit_plant_id, edit_message_id, edit_back_target;
 *      бот шлёт промпт «Введи новое имя…».
 *   2) Юзер присылает текст — этот handler его валидирует, сохраняет имя
 *      и редактирует исходное сообщение настроек в актуальное состояние.
 *
 * /cancel ловится на уровне диспетчера до state-резолвера.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingPlantRenameStateHandler implements StateHandler {

    private final UserService userService;
    private final PlantService plantService;
    private final PlantCardService plantCardService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_PLANT_RENAME;
    }

    @Override
    @Transactional
    public void handle(User user, Update update, TelegramClient client) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            sendHint(client, user.getTelegramChatId(),
                    "✏️ Пришли новое имя текстом или нажми «Отмена».");
            return;
        }

        String newName = update.getMessage().getText().trim();
        if (!PlantService.isValidPlantName(newName)) {
            sendHint(client, user.getTelegramChatId(),
                    "❌ Имя должно быть от 1 до 100 символов и не пустым. Попробуй ещё раз:");
            return;
        }

        Map<String, Object> stateData = user.getStateData();
        Long plantId = EditStateData.plantId(stateData);
        String backTarget = EditStateData.backTarget(stateData);

        if (plantId == null) {
            log.error("edit_plant_id missing for user {} in rename flow",
                    user.getTelegramChatId());
            userService.resetToIdle(user);
            sendHint(client, user.getTelegramChatId(),
                    "❌ Контекст редактирования утерян. Открой карточку и попробуй снова.");
            return;
        }

        try {
            plantService.renamePlant(user.getId(), plantId, newName);
        } catch (IllegalArgumentException e) {
            sendHint(client, user.getTelegramChatId(), "❌ " + e.getMessage());
            return;
        }

        userService.resetToIdle(user);
        sendHint(client, user.getTelegramChatId(), "✅ Имя обновлено: " + newName);
        // messageId=null → пришлём свежие настройки новым сообщением вниз чата,
        // чтобы юзер мог сразу продолжить редактировать без скроллинга вверх.
        // Старое сообщение с настройками останется как есть (немного устаревшим), это ок.
        plantCardService.showSettingsScreen(user, plantId, null, backTarget, client);
    }

    private void sendHint(TelegramClient client, Long chatId, String text) {
        try {
            client.execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send rename hint", e);
        }
    }
}

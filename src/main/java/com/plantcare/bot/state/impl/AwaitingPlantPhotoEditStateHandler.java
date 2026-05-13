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
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Шаг редактирования: перезаливка фото растения (issue #27).
 *
 * Отличается от AwaitingPlantPhotoStateHandler (флоу создания) тем, что не
 * вызывает finishPlantCreation — после сохранения file_id просто возвращает
 * пользователя на экран настроек.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingPlantPhotoEditStateHandler implements StateHandler {

    private final UserService userService;
    private final PlantService plantService;
    private final PlantCardService plantCardService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_PLANT_PHOTO_EDIT;
    }

    @Override
    @Transactional
    public void handle(User user, Update update, TelegramClient client) {
        if (!update.hasMessage() || !update.getMessage().hasPhoto()) {
            sendHint(client, user.getTelegramChatId(),
                    "📷 Пришли фото картинкой или нажми «Отмена».");
            return;
        }

        List<PhotoSize> photos = update.getMessage().getPhoto();
        String fileId = photos.stream()
                .max(Comparator.comparingInt(p -> safeSize(p.getFileSize())))
                .map(PhotoSize::getFileId)
                .orElse(null);

        if (fileId == null || fileId.isBlank()) {
            sendHint(client, user.getTelegramChatId(),
                    "❌ Не удалось получить файл. Попробуй ещё раз.");
            return;
        }

        Map<String, Object> stateData = user.getStateData();
        Long plantId = EditStateData.plantId(stateData);
        String backTarget = EditStateData.backTarget(stateData);

        if (plantId == null) {
            log.error("edit_plant_id missing for user {} in photo-edit flow",
                    user.getTelegramChatId());
            userService.resetToIdle(user);
            sendHint(client, user.getTelegramChatId(),
                    "❌ Контекст редактирования утерян. Открой карточку и попробуй снова.");
            return;
        }

        try {
            plantService.updatePhotoFileId(user.getId(), plantId, fileId);
        } catch (RuntimeException e) {
            log.error("Failed to update photo for plant {}", plantId, e);
            sendHint(client, user.getTelegramChatId(),
                    "❌ Не удалось сохранить фото. Попробуй ещё раз.");
            return;
        }

        userService.resetToIdle(user);
        sendHint(client, user.getTelegramChatId(), "✅ Фото обновлено");
        // Свежее меню новым сообщением вниз — чтобы юзер продолжил редактировать.
        plantCardService.showSettingsScreen(user, plantId, null, backTarget, client);
    }

    private int safeSize(Integer size) {
        return size == null ? 0 : size;
    }

    private void sendHint(TelegramClient client, Long chatId, String text) {
        try {
            client.execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send photo-edit hint", e);
        }
    }
}

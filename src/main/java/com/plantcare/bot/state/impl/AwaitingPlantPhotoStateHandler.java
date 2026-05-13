package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.service.PlantCardService;
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
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Comparator;
import java.util.List;

/**
 * Шаг создания растения: загрузка фото (опционально).
 *
 * Сценарии:
 * - Юзер шлёт картинку      → берём file_id самого большого PhotoSize, сохраняем в БД
 *                              и показываем карточку растения через sendPhoto.
 * - PHOTO:SKIP              → пропускаем шаг, показываем карточку как текст с плейсхолдером.
 * - Любой другой ввод       → подсказываем, что нужно прислать фото или нажать «Пропустить».
 *
 * Сами файлы на сервер не скачиваем — храним только file_id (бесплатно и быстро,
 * Telegram сам отдаёт файлы при последующих sendPhoto).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingPlantPhotoStateHandler implements StateHandler {

    private static final String PLANT_ID_KEY = "plant_id";
    private static final String SKIP_CALLBACK = "PHOTO:SKIP";

    private final UserService userService;
    private final PlantService plantService;
    private final PlantRepository plantRepository;
    private final PlantCardService plantCardService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_PLANT_PHOTO;
    }

    @Override
    @Transactional
    public void handle(User user, Update update, TelegramClient client) {
        if (update.hasCallbackQuery()) {
            handleCallback(user, update, client);
            return;
        }

        if (update.hasMessage() && update.getMessage().hasPhoto()) {
            handlePhoto(user, update, client);
            return;
        }

        // Любой другой ввод (текст / стикер / документ) — подсказка
        sendHint(user.getTelegramChatId(), client);
    }

    private void handleCallback(User user, Update update, TelegramClient client) {
        String data = update.getCallbackQuery().getData();
        answerCallback(update, client);

        if (!SKIP_CALLBACK.equals(data)) {
            log.debug("Ignoring callback in AWAITING_PLANT_PHOTO: {}", data);
            return;
        }

        Plant plant = findPlantFromStateData(user, client);

        if (plant == null) {
            return;
        }

        log.info(
                "User {} skipped photo upload for plant {}",
                user.getTelegramChatId(),
                plant.getId()
        );

        plantCardService.finishPlantCreation(user, plant, client);
    }

    private void handlePhoto(User user, Update update, TelegramClient client) {
        List<PhotoSize> photos = update.getMessage().getPhoto();

        if (photos == null || photos.isEmpty()) {
            sendHint(user.getTelegramChatId(), client);
            return;
        }

        // Telegram присылает несколько размеров одного и того же фото.
        // Сохраняем file_id самого большого — он же подходит и для меньших превью
        // (Telegram сам отдаёт нужный размер при sendPhoto).
        String fileId = photos.stream()
                .max(Comparator.comparingInt(p -> safeSize(p.getFileSize())))
                .map(PhotoSize::getFileId)
                .orElse(null);

        if (fileId == null || fileId.isBlank()) {
            log.warn("Received photo from user {} but no fileId could be extracted",
                    user.getTelegramChatId());
            sendHint(user.getTelegramChatId(), client);
            return;
        }

        Plant plant = findPlantFromStateData(user, client);

        if (plant == null) {
            return;
        }

        Plant updated;
        try {
            updated = plantService.updatePhotoFileId(user.getId(), plant.getId(), fileId);
        } catch (RuntimeException e) {
            log.error("Failed to save photo_file_id for plant {} (user {})",
                    plant.getId(), user.getTelegramChatId(), e);
            sendText(client, user.getTelegramChatId(),
                    "❌ Не удалось сохранить фото. Попробуй ещё раз или нажми «Пропустить».");
            return;
        }

        log.info(
                "Saved photo file_id for plant {} (user {})",
                updated.getId(),
                user.getTelegramChatId()
        );

        plantCardService.finishPlantCreation(user, updated, client);
    }

    private int safeSize(Integer size) {
        return size == null ? 0 : size;
    }

    private Plant findPlantFromStateData(User user, TelegramClient client) {
        Object plantIdValue = user.getStateData() != null
                ? user.getStateData().get(PLANT_ID_KEY)
                : null;

        if (plantIdValue == null) {
            log.error("plant_id not found in stateData for user {}", user.getTelegramChatId());
            sendText(client, user.getTelegramChatId(),
                    "❌ Не удалось найти растение. Попробуй добавить его заново.");
            userService.resetToIdle(user);
            return null;
        }

        Long plantId;

        try {
            plantId = Long.parseLong(String.valueOf(plantIdValue));
        } catch (NumberFormatException e) {
            log.error("Invalid plant_id={} for user {}", plantIdValue, user.getTelegramChatId(), e);
            sendText(client, user.getTelegramChatId(),
                    "❌ Некорректные данные растения. Попробуй добавить его заново.");
            userService.resetToIdle(user);
            return null;
        }

        Plant plant = plantRepository
                .findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .orElse(null);

        if (plant == null) {
            log.error("Plant {} not found during photo step for user {}",
                    plantId, user.getTelegramChatId());
            sendText(client, user.getTelegramChatId(),
                    "❌ Растение не найдено. Попробуй добавить его заново.");
            userService.resetToIdle(user);
            return null;
        }

        return plant;
    }

    private void sendHint(Long chatId, TelegramClient client) {
        sendText(client, chatId,
                "📸 Пришли фото растения как картинку или нажми «Пропустить».");
    }

    private void sendText(TelegramClient client, Long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send photo-step message", e);
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

package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

/**
 * Отвечает за единый способ показать карточку растения пользователю.
 *
 * Логика выбора способа отправки:
 *   - если у растения сохранён photoFileId → шлём SendPhoto с подписью (caption)
 *   - иначе → обычное SendMessage с эмодзи-плейсхолдером
 *
 * Сами файлы не скачиваем: Telegram хранит фото у себя, нам достаточно file_id.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlantCardService {

    /**
     * Максимальная длина caption у sendPhoto в Telegram Bot API — 1024 символа.
     * Карточка создания обычно короткая, но обрезаем на всякий случай.
     */
    private static final int CAPTION_MAX_LENGTH = 1024;

    private final PlantService plantService;
    private final MainMenuService mainMenuService;
    private final UserService userService;

    /**
     * Финальный шаг создания растения:
     *   1) показать карточку (с фото или без)
     *   2) сбросить пользователя в IDLE и очистить stateData
     *   3) показать главное меню
     */
    @Transactional
    public void finishPlantCreation(User user, Plant plant, TelegramClient client) {
        sendCreatedPlantCard(user, plant, client);

        userService.resetToIdle(user);
        mainMenuService.sendMainMenu(user, client);

        log.info(
                "Plant creation completed for user {}, plant={}",
                user.getTelegramChatId(),
                plant.getId()
        );
    }

    /**
     * Шлёт карточку только что созданного растения.
     * Если фото загружено — sendPhoto с подписью, иначе sendMessage.
     */
    public void sendCreatedPlantCard(User user, Plant plant, TelegramClient client) {
        List<CareSchedule> schedules = plantService.getActiveSchedules(plant.getId());

        String caption = buildCreatedCardText(plant, schedules);

        if (plant.getPhotoFileId() != null && !plant.getPhotoFileId().isBlank()) {
            sendPhotoCard(user.getTelegramChatId(), plant.getPhotoFileId(), caption, client);
        } else {
            sendTextCard(user.getTelegramChatId(), caption, client);
        }
    }

    private void sendPhotoCard(Long chatId, String fileId, String caption, TelegramClient client) {
        SendPhoto photo = SendPhoto.builder()
                .chatId(chatId.toString())
                .photo(new InputFile(fileId))
                .caption(truncate(caption, CAPTION_MAX_LENGTH))
                .parseMode("Markdown")
                .build();

        try {
            client.execute(photo);
        } catch (TelegramApiException e) {
            log.error("Failed to send plant card as photo (chatId={}), fallback to text", chatId, e);
            // Fallback: если sendPhoto не прошёл (например file_id протух), хотя бы покажем текст.
            sendTextCard(chatId, caption, client);
        }
    }

    private void sendTextCard(Long chatId, String text, TelegramClient client) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("Markdown")
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send plant card as text (chatId={})", chatId, e);
        }
    }

    private String buildCreatedCardText(Plant plant, List<CareSchedule> schedules) {
        StringBuilder text = new StringBuilder();

        text.append("✅ Растение добавлено!\n\n");
        text.append("🌿 *").append(plant.getName()).append("*\n");

        if (plant.getLocation() != null) {
            text.append("📍 Комната: ")
                    .append(plant.getLocation().getDisplayName())
                    .append("\n");
        }

        // Плейсхолдер, если фото не было — пользователь видит, что эта часть карточки осознанно пустая.
        if (plant.getPhotoFileId() == null || plant.getPhotoFileId().isBlank()) {
            text.append("📷 Фото: не загружено\n");
        }

        if (!schedules.isEmpty()) {
            text.append("\n📅 Напоминания:\n");

            for (CareSchedule schedule : schedules) {
                text.append("• ")
                        .append(formatTaskType(schedule.getTaskType()))
                        .append(" каждые ")
                        .append(schedule.getIntervalDays())
                        .append(" дн.\n");
            }
        }

        return text.toString();
    }

    private String formatTaskType(TaskType taskType) {
        return switch (taskType) {
            case WATERING -> "💧 Полив";
            case MISTING -> "💨 Опрыскивание";
            case FERTILIZING -> "🌿 Удобрение";
        };
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 1) + "…";
    }
}

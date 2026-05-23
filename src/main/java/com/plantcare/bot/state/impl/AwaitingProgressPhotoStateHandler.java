package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.PlantProgressPhoto;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.PhotoProgressCardService;
import com.plantcare.bot.service.PhotoProgressService;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
import com.plantcare.bot.util.TimezoneSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * Обработчик состояния {@link ConversationState#AWAITING_PROGRESS_PHOTO} (issue #72):
 * ожидание фото для таймлайна фото-прогресса.
 *
 * <p>Сценарии:
 * <ul>
 *     <li>Пользователь шлёт фото → сохраняем самое крупное превью в БД,
 *         возвращаем в IDLE, показываем подтверждение со следующей датой.</li>
 *     <li>Callback {@code PHOTO_PROGRESS:CANCEL} → сбрасываем state, открываем
 *         экран фото-прогресса.</li>
 *     <li>Любой другой ввод → подсказываем, что нужно фото (или Cancel).</li>
 * </ul>
 *
 * <p>plantId хранится в {@code user.stateData} под ключом
 * {@link #STATE_KEY_PLANT_ID} — кладётся при переходе в это состояние из
 * экрана фото-прогресса (callback {@code PHOTO_PROGRESS:ADD:{plantId}}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingProgressPhotoStateHandler implements StateHandler {

    public static final String STATE_KEY_PLANT_ID = "photo_progress_plant_id";
    private static final String CANCEL_CALLBACK = "PHOTO_PROGRESS:CANCEL";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final UserService userService;
    private final PhotoProgressService photoProgressService;
    private final PhotoProgressCardService photoProgressCardService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_PROGRESS_PHOTO;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        if (update.hasCallbackQuery()) {
            handleCallback(user, update, client);
            return;
        }

        if (update.hasMessage() && update.getMessage().hasPhoto()) {
            handlePhoto(user, update, client);
            return;
        }

        sendHint(user.getTelegramChatId(), client);
    }

    private void handleCallback(User user, Update update, TelegramClient client) {
        String data = update.getCallbackQuery().getData();
        answerCallback(update, client);

        if (!CANCEL_CALLBACK.equals(data)) {
            log.debug("Ignoring callback in AWAITING_PROGRESS_PHOTO: {}", data);
            return;
        }

        Long plantId = readPlantId(user);
        userService.resetToIdle(user);
        if (plantId != null) {
            photoProgressCardService.showPhotoProgressScreen(user, plantId, null, client);
        }
    }

    private void handlePhoto(User user, Update update, TelegramClient client) {
        Long plantId = readPlantId(user);
        if (plantId == null) {
            log.warn("Photo received but plantId missing in stateData (user {})",
                    user.getTelegramChatId());
            sendText(client, user.getTelegramChatId(),
                    "❌ Не нашёл контекст растения. Открой экран фото-прогресса заново.");
            userService.resetToIdle(user);
            return;
        }

        List<PhotoSize> photos = update.getMessage().getPhoto();
        if (photos == null || photos.isEmpty()) {
            sendHint(user.getTelegramChatId(), client);
            return;
        }

        // Telegram возвращает несколько размеров; сохраняем самый крупный.
        String fileId = photos.stream()
                .max(Comparator.comparingInt(p -> safeSize(p.getFileSize())))
                .map(PhotoSize::getFileId)
                .orElse(null);
        if (fileId == null || fileId.isBlank()) {
            sendHint(user.getTelegramChatId(), client);
            return;
        }

        PlantProgressPhoto saved;
        try {
            saved = photoProgressService.addPhoto(user.getId(), plantId, fileId);
        } catch (IllegalStateException e) {
            // Антиспам: уже было фото за последние 24ч.
            sendText(client, user.getTelegramChatId(), "⚠️ " + e.getMessage());
            userService.resetToIdle(user);
            photoProgressCardService.showPhotoProgressScreen(user, plantId, null, client);
            return;
        } catch (IllegalArgumentException e) {
            sendText(client, user.getTelegramChatId(), "❌ Растение не найдено.");
            userService.resetToIdle(user);
            return;
        }

        userService.resetToIdle(user);

        // Подтверждение: «Фото добавлено. Следующее напоминание — {date}».
        StringBuilder confirm = new StringBuilder("✅ Фото добавлено.");
        if (saved.getPlant().getPhotoProgressFrequency() != null
                && saved.getPlant().getPhotoProgressFrequency().isEnabled()
                && saved.getPlant().getNextPhotoDueAt() != null) {
            LocalDate next = TimezoneSupport.dateInUserZone(
                    saved.getPlant().getNextPhotoDueAt(), user);
            confirm.append(" Следующее напоминание — ").append(next.format(DATE_FMT)).append(".");
        }
        sendText(client, user.getTelegramChatId(), confirm.toString());

        photoProgressCardService.showPhotoProgressScreen(user, plantId, null, client);
    }

    private Long readPlantId(User user) {
        if (user.getStateData() == null) {
            return null;
        }
        Object raw = user.getStateData().get(STATE_KEY_PLANT_ID);
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void sendHint(Long chatId, TelegramClient client) {
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("⬅️ Отмена")
                                .callbackData(CANCEL_CALLBACK)
                                .build()
                )))
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text("📸 Пришли фото как картинку — оно попадёт в таймлайн.")
                .replyMarkup(keyboard)
                .build();
        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send photo-progress hint ({}): {}", chatId, e.getMessage());
        }
    }

    private int safeSize(Integer size) {
        return size == null ? 0 : size;
    }

    private void sendText(TelegramClient client, Long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .build();
        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send text ({}): {}", chatId, e.getMessage());
        }
    }

    private void answerCallback(Update update, TelegramClient client) {
        try {
            client.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(update.getCallbackQuery().getId())
                    .build());
        } catch (Exception e) {
            log.warn("Failed to answer callback: {}", e.getMessage());
        }
    }
}

package com.plantcare.bot.service;

import com.plantcare.core.service.PhotoProgressService;

import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.PlantProgressPhoto;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.PhotoProgressFrequency;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.util.TimezoneSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Рендер экранов фото-прогресса (issue #72): главный экран, выбор частоты,
 * история, меню сравнения, отправка пары «до/после».
 *
 * <p>Логику и доступ к БД выносим в {@link PhotoProgressService}; здесь —
 * только сборка текста и клавиатур + отправка через Telegram.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoProgressCardService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    /** Сколько последних фото показывать в экране «Выбрать две даты». */
    private static final int PICK_LIST_SIZE = 10;

    private final PhotoProgressService photoProgressService;
    private final PlantRepository plantRepository;

    /**
     * Главный экран фото-прогресса для растения.
     */
    public void showPhotoProgressScreen(
            User user, Long plantId, Integer messageId, TelegramClient client
    ) {
        Plant plant = plantRepository
                .findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .orElse(null);
        if (plant == null) {
            sendText(user.getTelegramChatId(), "❌ Растение не найдено.", client);
            return;
        }

        long total = photoProgressService.countHistory(user.getId(), plantId);
        PlantProgressPhoto last = photoProgressService.getHistory(user.getId(), plantId, 0)
                .stream().findFirst().orElse(null);

        StringBuilder text = new StringBuilder();
        text.append("📸 *Фото-прогресс — ").append(escapeMd(plant.getName())).append("*\n\n");
        text.append("Режим: *").append(frequencyLabel(plant.getPhotoProgressFrequency())).append("*\n");

        if (last != null) {
            LocalDate when = TimezoneSupport.dateInUserZone(last.getTakenAt(), user);
            text.append("Последнее фото: ").append(when.format(DATE_FMT)).append("\n");
        } else {
            text.append("Последнее фото: ещё нет\n");
        }
        text.append("Всего фото: ").append(total).append("\n");

        if (plant.getPhotoProgressFrequency() != null
                && plant.getPhotoProgressFrequency().isEnabled()
                && plant.getNextPhotoDueAt() != null) {
            LocalDate next = TimezoneSupport.dateInUserZone(plant.getNextPhotoDueAt(), user);
            text.append("Следующий пуш: ").append(next.format(DATE_FMT)).append("\n");
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⚙️ Изменить режим")
                        .callbackData("PHOTO_PROGRESS:FREQ:" + plantId)
                        .build()
        )));

        InlineKeyboardRow actions = new InlineKeyboardRow();
        actions.add(InlineKeyboardButton.builder()
                .text("➕ Добавить фото")
                .callbackData("PHOTO_PROGRESS:ADD:" + plantId)
                .build());
        if (total > 0) {
            actions.add(InlineKeyboardButton.builder()
                    .text("🖼 История")
                    .callbackData("PHOTO_PROGRESS:HISTORY:" + plantId + ":0")
                    .build());
        }
        if (total >= 2) {
            actions.add(InlineKeyboardButton.builder()
                    .text("🆚 Сравнить")
                    .callbackData("PHOTO_PROGRESS:COMPARE:" + plantId)
                    .build());
        }
        rows.add(actions);

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ К карточке")
                        .callbackData("PLANT:VIEW:" + plantId)
                        .build()
        )));

        sendOrEdit(user.getTelegramChatId(), messageId, text.toString(),
                InlineKeyboardMarkup.builder().keyboard(rows).build(), client);
    }

    /**
     * Экран выбора частоты.
     */
    public void showFrequencyChoice(
            User user, Long plantId, Integer messageId, TelegramClient client
    ) {
        Plant plant = plantRepository
                .findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .orElse(null);
        if (plant == null) {
            sendText(user.getTelegramChatId(), "❌ Растение не найдено.", client);
            return;
        }

        String text = "⚙️ *Режим фото-прогресса — "
                + escapeMd(plant.getName()) + "*\n\n"
                + "Выбери, как часто бот будет напоминать обновить фото:";

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (PhotoProgressFrequency freq : PhotoProgressFrequency.values()) {
            String mark = freq == plant.getPhotoProgressFrequency() ? "✅ " : "";
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text(mark + frequencyLabel(freq))
                            .callbackData("PHOTO_PROGRESS:FREQ:SET:" + plantId + ":" + freq.name())
                            .build()
            )));
        }
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ Назад")
                        .callbackData("PHOTO_PROGRESS:VIEW:" + plantId)
                        .build()
        )));

        sendOrEdit(user.getTelegramChatId(), messageId, text,
                InlineKeyboardMarkup.builder().keyboard(rows).build(), client);
    }

    /**
     * История фото с пагинацией.
     */
    public void showHistory(
            User user, Long plantId, int page, Integer messageId, TelegramClient client
    ) {
        Plant plant = plantRepository
                .findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .orElse(null);
        if (plant == null) {
            sendText(user.getTelegramChatId(), "❌ Растение не найдено.", client);
            return;
        }

        long total = photoProgressService.countHistory(user.getId(), plantId);
        int pageSize = PhotoProgressService.HISTORY_PAGE_SIZE;
        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / pageSize);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));

        List<PlantProgressPhoto> photos = photoProgressService.getHistory(
                user.getId(), plantId, safePage);

        StringBuilder text = new StringBuilder();
        text.append("🖼 *История фото — ").append(escapeMd(plant.getName())).append("*\n\n");

        if (photos.isEmpty()) {
            text.append("Здесь пока пусто. Нажми «➕ Добавить фото» в экране фото-прогресса.");
        } else {
            int idx = safePage * pageSize + 1;
            for (PlantProgressPhoto photo : photos) {
                LocalDate date = TimezoneSupport.dateInUserZone(photo.getTakenAt(), user);
                text.append(idx++).append(". ")
                        .append(date.format(DATE_FMT)).append("\n");
            }
            if (totalPages > 1) {
                text.append("\n_Страница ").append(safePage + 1)
                        .append(" из ").append(totalPages).append("_");
            }
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();

        // Открытие конкретного фото — на каждый элемент страницы.
        for (PlantProgressPhoto photo : photos) {
            LocalDate date = TimezoneSupport.dateInUserZone(photo.getTakenAt(), user);
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text("🖼 " + date.format(DATE_FMT))
                            .callbackData("PHOTO_PROGRESS:HISTORY:VIEW:" + photo.getId())
                            .build()
            )));
        }

        if (totalPages > 1) {
            InlineKeyboardRow nav = new InlineKeyboardRow();
            if (safePage > 0) {
                nav.add(InlineKeyboardButton.builder()
                        .text("← Назад")
                        .callbackData("PHOTO_PROGRESS:HISTORY:" + plantId + ":" + (safePage - 1))
                        .build());
            }
            nav.add(InlineKeyboardButton.builder()
                    .text((safePage + 1) + "/" + totalPages)
                    .callbackData("PHOTO_PROGRESS:HISTORY:" + plantId + ":" + safePage)
                    .build());
            if (safePage < totalPages - 1) {
                nav.add(InlineKeyboardButton.builder()
                        .text("Вперёд →")
                        .callbackData("PHOTO_PROGRESS:HISTORY:" + plantId + ":" + (safePage + 1))
                        .build());
            }
            rows.add(nav);
        }

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ Назад")
                        .callbackData("PHOTO_PROGRESS:VIEW:" + plantId)
                        .build()
        )));

        sendOrEdit(user.getTelegramChatId(), messageId, text.toString(),
                InlineKeyboardMarkup.builder().keyboard(rows).build(), client);
    }

    /**
     * Открыть одно конкретное фото отдельным sendPhoto.
     */
    public void showPhoto(User user, Long photoId, TelegramClient client) {
        PlantProgressPhoto photo = photoProgressService
                .getPhotoForUser(user.getId(), photoId)
                .orElse(null);
        if (photo == null) {
            sendText(user.getTelegramChatId(), "❌ Фото не найдено.", client);
            return;
        }

        LocalDate date = TimezoneSupport.dateInUserZone(photo.getTakenAt(), user);
        SendPhoto send = SendPhoto.builder()
                .chatId(user.getTelegramChatId().toString())
                .photo(new InputFile(photo.getTelegramFileId()))
                .caption("🖼 " + date.format(DATE_FMT))
                .build();
        try {
            client.execute(send);
        } catch (TelegramApiException e) {
            log.error("Failed to send progress photo (id={}): {}", photoId, e.getMessage());
            sendText(user.getTelegramChatId(), "❌ Не удалось отправить фото.", client);
        }
    }

    /**
     * Меню «Сравнить»: первое vs последнее / месяц vs сейчас / выбрать вручную.
     */
    public void showCompareMenu(
            User user, Long plantId, Integer messageId, TelegramClient client
    ) {
        Plant plant = plantRepository
                .findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .orElse(null);
        if (plant == null) {
            sendText(user.getTelegramChatId(), "❌ Растение не найдено.", client);
            return;
        }

        long total = photoProgressService.countHistory(user.getId(), plantId);
        if (total < 2) {
            sendText(user.getTelegramChatId(),
                    "Нужно минимум 2 фото для сравнения.", client);
            return;
        }

        String text = "🆚 *Сравнить фото — " + escapeMd(plant.getName()) + "*\n\n"
                + "Выбери, что сравнить:";

        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("📅 Первое vs Последнее")
                        .callbackData("PHOTO_PROGRESS:COMPARE:FIRST_LAST:" + plantId)
                        .build()
        )));
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("🗓 Месяц назад vs Сейчас")
                        .callbackData("PHOTO_PROGRESS:COMPARE:MONTH_NOW:" + plantId)
                        .build()
        )));
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("✋ Выбрать две даты")
                        .callbackData("PHOTO_PROGRESS:COMPARE:PICK:" + plantId)
                        .build()
        )));
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ Назад")
                        .callbackData("PHOTO_PROGRESS:VIEW:" + plantId)
                        .build()
        )));

        sendOrEdit(user.getTelegramChatId(), messageId, text,
                InlineKeyboardMarkup.builder().keyboard(rows).build(), client);
    }

    /**
     * Список последних фото для UI «Выбрать две даты». Если {@code leftId == null} —
     * пользователь выбирает левый снимок; иначе — правый.
     */
    public void showPickList(
            User user, Long plantId, Long leftId, Integer messageId, TelegramClient client
    ) {
        Plant plant = plantRepository
                .findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .orElse(null);
        if (plant == null) {
            sendText(user.getTelegramChatId(), "❌ Растение не найдено.", client);
            return;
        }

        List<PlantProgressPhoto> recent = photoProgressService
                .getRecent(user.getId(), plantId, PICK_LIST_SIZE);

        StringBuilder text = new StringBuilder();
        text.append("✋ *Выбор фото для сравнения — ")
                .append(escapeMd(plant.getName())).append("*\n\n");
        if (leftId == null) {
            text.append("Шаг 1/2: выбери первое фото (\"до\"):");
        } else {
            text.append("Шаг 2/2: выбери второе фото (\"после\"):");
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (PlantProgressPhoto photo : recent) {
            // Если левый уже выбран — не показываем его в списке «правых».
            if (leftId != null && leftId.equals(photo.getId())) {
                continue;
            }
            LocalDate date = TimezoneSupport.dateInUserZone(photo.getTakenAt(), user);
            String cb = leftId == null
                    ? "PHOTO_PROGRESS:COMPARE:LEFT:" + plantId + ":" + photo.getId()
                    : "PHOTO_PROGRESS:COMPARE:DO:" + plantId + ":" + leftId + ":" + photo.getId();
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text(date.format(DATE_FMT))
                            .callbackData(cb)
                            .build()
            )));
        }
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ Назад")
                        .callbackData("PHOTO_PROGRESS:COMPARE:" + plantId)
                        .build()
        )));

        sendOrEdit(user.getTelegramChatId(), messageId, text.toString(),
                InlineKeyboardMarkup.builder().keyboard(rows).build(), client);
    }

    /**
     * Отправить пару фото «до / после» в чат с подписями и summary
     * «Прошло: N дней».
     */
    public void sendComparison(User user, PhotoProgressService.PhotoPair pair, TelegramClient client) {
        LocalDate beforeDate = TimezoneSupport.dateInUserZone(pair.before().getTakenAt(), user);
        LocalDate afterDate = TimezoneSupport.dateInUserZone(pair.after().getTakenAt(), user);
        long days = ChronoUnit.DAYS.between(
                pair.before().getTakenAt().toLocalDate(),
                pair.after().getTakenAt().toLocalDate()
        );

        SendPhoto sendBefore = SendPhoto.builder()
                .chatId(user.getTelegramChatId().toString())
                .photo(new InputFile(pair.before().getTelegramFileId()))
                .caption("⏮ До — " + beforeDate.format(DATE_FMT))
                .build();
        SendPhoto sendAfter = SendPhoto.builder()
                .chatId(user.getTelegramChatId().toString())
                .photo(new InputFile(pair.after().getTelegramFileId()))
                .caption("⏭ После — " + afterDate.format(DATE_FMT))
                .build();
        SendMessage summary = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("Прошло: " + days + " дн.")
                .build();
        try {
            client.execute(sendBefore);
            client.execute(sendAfter);
            client.execute(summary);
        } catch (TelegramApiException e) {
            log.error("Failed to send comparison: {}", e.getMessage());
            sendText(user.getTelegramChatId(), "❌ Не удалось отправить сравнение.", client);
        }
    }

    /**
     * Ответ на callback (alert / no-op).
     */
    public void answerCallback(TelegramClient client, String callbackId, String text) {
        if (callbackId == null) {
            return;
        }
        try {
            client.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .text(text == null ? "" : text)
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Failed to answer callback {}: {}", callbackId, e.getMessage());
        }
    }

    private String frequencyLabel(PhotoProgressFrequency freq) {
        if (freq == null) {
            return "Выключено";
        }
        return switch (freq) {
            case OFF -> "Выключено";
            case P2W -> "Раз в 2 недели";
            case P1M -> "Раз в месяц";
        };
    }

    private void sendOrEdit(
            Long chatId,
            Integer messageId,
            String text,
            InlineKeyboardMarkup keyboard,
            TelegramClient client
    ) {
        if (messageId != null) {
            EditMessageText edit = EditMessageText.builder()
                    .chatId(chatId.toString())
                    .messageId(messageId)
                    .text(text)
                    .parseMode("Markdown")
                    .replyMarkup(keyboard)
                    .build();
            try {
                client.execute(edit);
                return;
            } catch (TelegramApiException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("message is not modified")) {
                    return;
                }
                log.warn("Failed to edit photo-progress message ({}): {}", chatId, msg);
                // fallback на отправку новым сообщением
            }
        }
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("Markdown")
                .replyMarkup(keyboard)
                .build();
        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send photo-progress message ({}): {}", chatId, e.getMessage());
        }
    }

    private void sendText(Long chatId, String text, TelegramClient client) {
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

    private String escapeMd(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("`", "\\`");
    }
}

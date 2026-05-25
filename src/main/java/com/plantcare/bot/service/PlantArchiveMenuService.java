package com.plantcare.bot.service;

import com.plantcare.core.service.PlantArchiveService;

import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.util.TimezoneSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * UI «Архив (Memorial)» — экран списка архивных растений и карточка
 * отдельного архивного растения (issue #117).
 *
 * <p>Формулировки специально мягкие («в архиве с», не «погибла»). Карточка
 * включает дату завода, дату архивации, краткую статистику поливов и три
 * действия: восстановить, удалить навсегда, история (история — пока «в разработке»,
 * чтобы не плодить полноценную фичу сверх issue).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlantArchiveMenuService {

    private static final DateTimeFormatter ARCHIVE_DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.forLanguageTag("ru"));

    private final PlantArchiveService plantArchiveService;
    private final PlantCardService plantCardService;

    /**
     * Список архивных растений «📦 Архив». Заменяет текущее сообщение через
     * EditMessageText (мы туда пришли из {@code MyPlants} списка), либо шлёт
     * новым сообщением, если {@code messageId == null}.
     */
    @Transactional(readOnly = true)
    public void showArchiveList(User user, Integer messageId, TelegramClient client) {
        List<Plant> archived = plantArchiveService.listArchived(user.getId());

        String text = buildListText(archived, user);
        InlineKeyboardMarkup keyboard = buildListKeyboard(archived);
        sendOrEdit(user.getTelegramChatId(), messageId, text, keyboard, client);
    }

    private String buildListText(List<Plant> plants, User user) {
        StringBuilder sb = new StringBuilder();
        sb.append("📦 *Архив*\n\n");

        if (plants.isEmpty()) {
            sb.append("Архив пуст.");
            return sb.toString();
        }

        for (Plant p : plants) {
            sb.append("• ")
                    .append(escapeMd(p.getName()))
                    .append(" — в архиве с ");

            LocalDate archivedDate = TimezoneSupport.dateInUserZone(p.getArchivedAt(), user);
            sb.append(archivedDate != null ? archivedDate.format(ARCHIVE_DATE_FMT) : "—");
            sb.append("\n");
        }
        return sb.toString();
    }

    private InlineKeyboardMarkup buildListKeyboard(List<Plant> plants) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (Plant p : plants) {
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text("🌿 " + p.getName())
                            .callbackData("ARCHIVE:VIEW:" + p.getId())
                            .build()
            )));
        }

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ К растениям")
                        .callbackData("PLANT:LIST")
                        .build()
        )));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * Карточка архивного растения. Показывает имя, фото (если есть отдельным
     * сообщением — текстовая часть остаётся редактируемой), даты завода и
     * архивации, краткую статистику и три действия.
     */
    @Transactional(readOnly = true)
    public void showArchiveCard(
            User user, Long plantId, Integer messageId, TelegramClient client
    ) {
        Plant plant;
        try {
            plant = plantArchiveService.getArchivedOrThrow(user.getId(), plantId);
        } catch (Exception e) {
            log.warn("Archive card: plant {} not found for user {}", plantId, user.getId());
            sendTextMessage(user.getTelegramChatId(),
                    "❌ Растение не найдено в архиве.", client);
            return;
        }

        // Тач lazy-полей, которые понадобятся в рендере.
        if (plant.getLocation() != null) {
            plant.getLocation().getName();
        }

        long waterings = plantArchiveService.countWaterings(plant.getId());
        long months = plantArchiveService.monthsLived(plant);
        String text = buildCardText(plant, user, waterings, months);
        InlineKeyboardMarkup keyboard = buildCardKeyboard(plant.getId());

        sendOrEdit(user.getTelegramChatId(), messageId, text, keyboard, client);
    }

    private String buildCardText(Plant plant, User user, long waterings, long months) {
        StringBuilder sb = new StringBuilder();
        sb.append("📦 *").append(escapeMd(plant.getName())).append("*\n");

        // «С тобой с …» — через хелпер из PlantCardService (общая логика, общий
        // формат). Reference-дата = дата архивации, чтобы возраст не «продолжал расти»
        // после смерти растения.
        if (plant.getAcquiredAt() != null) {
            LocalDate archivedDate = TimezoneSupport.dateInUserZone(plant.getArchivedAt(), user);
            StringBuilder line = new StringBuilder();
            plantCardService.appendAcquiredAtLine(line, plant.getAcquiredAt(), archivedDate);
            sb.append(line);
        }

        LocalDate archivedDate = TimezoneSupport.dateInUserZone(plant.getArchivedAt(), user);
        sb.append("📦 В архиве с ")
                .append(archivedDate != null ? archivedDate.format(ARCHIVE_DATE_FMT) : "—")
                .append("\n");

        if (waterings > 0 && months > 0) {
            sb.append("\n💧 ")
                    .append(waterings).append(' ').append(pluralizeWaterings(waterings))
                    .append(" за ")
                    .append(months).append(' ').append(pluralizeMonths(months))
                    .append('\n');
        } else if (waterings > 0) {
            sb.append("\n💧 ")
                    .append(waterings).append(' ').append(pluralizeWaterings(waterings))
                    .append('\n');
        }

        return sb.toString();
    }

    private InlineKeyboardMarkup buildCardKeyboard(Long plantId) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        // «Полная история» — задел под отдельную фичу, в issue #117 её нет.
        // Кнопка отвечает alert'ом «в разработке» (см. callback handler).
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("📜 Полная история")
                        .callbackData("ARCHIVE:HISTORY:" + plantId)
                        .build()
        )));

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("♻️ Восстановить")
                        .callbackData("ARCHIVE:RESTORE:" + plantId)
                        .build(),
                InlineKeyboardButton.builder()
                        .text("🗑 Удалить навсегда")
                        .callbackData("ARCHIVE:DELETE_CONFIRM:" + plantId)
                        .build()
        )));

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ К архиву")
                        .callbackData("ARCHIVE:LIST")
                        .build()
        )));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * Экран подтверждения окончательного удаления.
     */
    @Transactional(readOnly = true)
    public void showDeleteConfirm(
            User user, Long plantId, Integer messageId, TelegramClient client
    ) {
        Plant plant;
        try {
            plant = plantArchiveService.getArchivedOrThrow(user.getId(), plantId);
        } catch (Exception e) {
            sendTextMessage(user.getTelegramChatId(),
                    "❌ Растение не найдено в архиве.", client);
            return;
        }

        String text = "🗑 *Удалить навсегда?*\n\n"
                + "🌿 " + escapeMd(plant.getName()) + "\n\n"
                + "_Удалить " + escapeMd(plant.getName()) + " навсегда? Восстановить будет нельзя._";

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("🗑 Да, удалить")
                                .callbackData("ARCHIVE:DELETE_FINAL:" + plant.getId())
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("⬅️ Отмена")
                                .callbackData("ARCHIVE:VIEW:" + plant.getId())
                                .build()
                )))
                .build();

        sendOrEdit(user.getTelegramChatId(), messageId, text, keyboard, client);
    }

    /**
     * Шлёт фото архивного растения отдельным сообщением (если есть file_id).
     * Карточка — текстовая, поэтому фото нельзя «встроить» в редактируемое сообщение,
     * шлём как separate sendPhoto.
     */
    public void sendArchivedPhotoIfPresent(User user, Plant plant, TelegramClient client) {
        if (plant.getPhotoFileId() == null || plant.getPhotoFileId().isBlank()) {
            return;
        }
        SendPhoto photo = SendPhoto.builder()
                .chatId(user.getTelegramChatId().toString())
                .photo(new InputFile(plant.getPhotoFileId()))
                .caption("📦 " + plant.getName())
                .build();
        try {
            client.execute(photo);
        } catch (TelegramApiException e) {
            log.warn("Failed to send archived plant photo (plant={}): {}",
                    plant.getId(), e.getMessage());
        }
    }

    // ===== низкоуровневые хелперы =====

    private void sendOrEdit(
            Long chatId, Integer messageId, String text,
            InlineKeyboardMarkup keyboard, TelegramClient client
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
                log.warn("Failed to edit archive screen (chat={}, msg={}): {}",
                        chatId, messageId, e.getMessage());
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
            log.error("Failed to send archive screen (chat={}): {}", chatId, e.getMessage());
        }
    }

    private void sendTextMessage(Long chatId, String text, TelegramClient client) {
        try {
            client.execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .parseMode("Markdown")
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send archive text message (chat={})", chatId, e);
        }
    }

    private String escapeMd(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("`", "\\`");
    }

    private static String pluralizeWaterings(long n) {
        long mod10 = Math.abs(n) % 10;
        long mod100 = Math.abs(n) % 100;
        if (mod10 == 1 && mod100 != 11) return "полив";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "полива";
        return "поливов";
    }

    private static String pluralizeMonths(long n) {
        long mod10 = Math.abs(n) % 10;
        long mod100 = Math.abs(n) % 100;
        if (mod10 == 1 && mod100 != 11) return "месяц";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "месяца";
        return "месяцев";
    }
}

package com.plantcare.bot.service;

import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.service.CalendarService.CareTask;
import com.plantcare.bot.service.CalendarService.DayView;
import com.plantcare.bot.service.CalendarService.WeekView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Рендер недельного календаря в сообщение Telegram (issue #52).
 *
 * Делим обязанности:
 *   - {@link CalendarService} собирает данные (проекция событий, кэш)
 *   - этот сервис превращает их в текст + клавиатуру и отправляет в чат
 *
 * Поддерживает два режима:
 *   - send: новое сообщение (по команде /calendar или кнопке из главного меню)
 *   - edit: редактирование того же сообщения (при листании ← / → в пагинации)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalendarMenuService {

    /** Лимит длины сообщения Telegram, при котором режем на части. */
    private static final int SOFT_LIMIT = CalendarService.TELEGRAM_MESSAGE_SOFT_LIMIT;

    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", new Locale("ru"));

    private final CalendarService calendarService;

    public void sendCalendar(User user, TelegramClient client) {
        sendCalendar(user, 0, null, client);
    }

    /**
     * @param messageId если не null — редактируем это сообщение (листание неделями),
     *                  иначе шлём новое.
     */
    public void sendCalendar(User user, int weekOffset, Integer messageId, TelegramClient client) {
        WeekView view = calendarService.buildWeekView(user, weekOffset);
        boolean groupByLocation = calendarService.distinctLocationsInWeek(view) >= 2;

        List<String> chunks = renderChunks(view, groupByLocation);

        // Клавиатура с пагинацией прикрепляется только к ПОСЛЕДНЕМУ сообщению,
        // чтобы кнопки оказались под всем содержимым недели.
        InlineKeyboardMarkup keyboard = buildKeyboard(view.offset());

        Long chatId = user.getTelegramChatId();
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            boolean isLast = i == chunks.size() - 1;
            InlineKeyboardMarkup kb = isLast ? keyboard : null;

            if (i == 0 && messageId != null) {
                // Первый чанк редактирует исходное сообщение. Дополнительные чанки
                // (если есть) уходят новыми сообщениями.
                editMessage(chatId, messageId, chunk, kb, client);
            } else {
                sendMessage(chatId, chunk, kb, client);
            }
        }
    }

    /**
     * Разбивает рендер недели на чанки ≤ SOFT_LIMIT символов. Старается резать
     * между днями (не разрывать день пополам). В крайнем случае — между задачами.
     */
    List<String> renderChunks(WeekView view, boolean groupByLocation) {
        StringBuilder header = new StringBuilder();
        header.append("📅 *Календарь ухода*\n");
        header.append("_").append(view.weekStart().format(SHORT_DATE))
                .append(" – ").append(view.weekEnd().format(SHORT_DATE)).append("_\n\n");

        List<String> dayBlocks = new ArrayList<>(view.days().size());
        for (DayView day : view.days()) {
            dayBlocks.add(renderDay(day, groupByLocation));
        }

        // Если на всю неделю задач нет — короткое сообщение «всё пусто».
        if (view.totalTasks() == 0) {
            StringBuilder empty = new StringBuilder(header);
            empty.append("🌱 На неделе пусто. Можно выдохнуть.");
            return List.of(empty.toString());
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);

        for (String block : dayBlocks) {
            if (current.length() + block.length() > SOFT_LIMIT && current.length() > header.length()) {
                chunks.add(current.toString());
                current = new StringBuilder();
                current.append("_(продолжение)_\n\n");
            }
            current.append(block);
        }

        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private String renderDay(DayView day, boolean groupByLocation) {
        StringBuilder sb = new StringBuilder();
        sb.append("📆 *").append(dayHeader(day)).append("*");

        if (day.isEmpty()) {
            sb.append(" — пусто 🌱\n\n");
            return sb.toString();
        }

        sb.append("\n");

        if (groupByLocation) {
            Map<Location, List<CareTask>> byLoc = calendarService.groupByLocation(day.tasks());
            for (Map.Entry<Location, List<CareTask>> entry : byLoc.entrySet()) {
                Location loc = entry.getKey();
                sb.append("  _")
                        .append(escapeMd(loc == null ? "Без комнаты" : loc.getDisplayName()))
                        .append("_:\n");
                for (CareTask t : entry.getValue()) {
                    sb.append("    ").append(formatTask(t)).append("\n");
                }
            }
        } else {
            for (CareTask t : day.tasks()) {
                sb.append("  ").append(formatTask(t)).append("\n");
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    private String dayHeader(DayView day) {
        String date = day.date().format(SHORT_DATE);
        if (day.isToday()) return "Сегодня (" + date + ")";
        if (day.isTomorrow()) return "Завтра (" + date + ")";
        if (day.isYesterday()) return "Вчера (" + date + ")";

        String weekday = day.date()
                .getDayOfWeek()
                .getDisplayName(TextStyle.SHORT, new Locale("ru"));
        return capitalize(weekday) + ", " + date;
    }

    private String formatTask(CareTask task) {
        return taskEmoji(task.taskType()) + " "
                + escapeMd(task.plant().getName())
                + " — " + taskVerb(task.taskType());
    }

    private String taskEmoji(TaskType type) {
        return switch (type) {
            case WATERING -> "💧";
            case MISTING -> "💨";
            case FERTILIZING -> "🌿";
        };
    }

    private String taskVerb(TaskType type) {
        return switch (type) {
            case WATERING -> "полить";
            case MISTING -> "опрыскать";
            case FERTILIZING -> "удобрить";
        };
    }

    private InlineKeyboardMarkup buildKeyboard(int weekOffset) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        InlineKeyboardRow nav = new InlineKeyboardRow();
        nav.add(InlineKeyboardButton.builder()
                .text("← Прошлая")
                .callbackData("cal:week:" + (weekOffset - 1))
                .build());
        if (weekOffset != 0) {
            nav.add(InlineKeyboardButton.builder()
                    .text("Сегодня")
                    .callbackData("cal:week:0")
                    .build());
        }
        nav.add(InlineKeyboardButton.builder()
                .text("Следующая →")
                .callbackData("cal:week:" + (weekOffset + 1))
                .build());
        rows.add(nav);

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ В главное меню")
                        .callbackData("MENU:BACK")
                        .build()
        )));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private void sendMessage(Long chatId, String text, InlineKeyboardMarkup keyboard, TelegramClient client) {
        SendMessage.SendMessageBuilder<?, ?> builder = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("Markdown");
        if (keyboard != null) builder.replyMarkup(keyboard);
        try {
            client.execute(builder.build());
        } catch (TelegramApiException e) {
            log.error("Failed to send calendar message to chat {}: {}", chatId, e.getMessage());
        }
    }

    private void editMessage(Long chatId, Integer messageId, String text,
                             InlineKeyboardMarkup keyboard, TelegramClient client) {
        EditMessageText.EditMessageTextBuilder<?, ?> builder = EditMessageText.builder()
                .chatId(chatId.toString())
                .messageId(messageId)
                .text(text)
                .parseMode("Markdown");
        if (keyboard != null) builder.replyMarkup(keyboard);
        try {
            client.execute(builder.build());
        } catch (TelegramApiException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("message is not modified")) return;
            log.warn("Edit calendar failed (chat={}, msg={}): {}, falling back to send",
                    chatId, messageId, e.getMessage());
            sendMessage(chatId, text, keyboard, client);
        }
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Минимальное экранирование Markdown: имена растений могут содержать */
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
}

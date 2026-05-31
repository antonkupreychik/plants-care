package com.plantcare.bot.service;

import com.plantcare.core.service.ShoppingListService;

import com.plantcare.core.domain.ShoppingItem;
import com.plantcare.core.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Экран «Список покупок» (issue #136): рендерит чек-лист и кнопки действий.
 * Бизнес-логику делегирует {@link ShoppingListService}, к репозиторию не ходит.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShoppingListMenuService {

    static final String EMPTY_LIST_TEXT = "Список пуст. Добавь, что нужно купить 🌱";

    private final ShoppingListService shoppingListService;

    public void sendShoppingList(User user, TelegramClient client) {
        List<ShoppingItem> items = shoppingListService.list(user.getId());

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(buildText(items))
                .replyMarkup(buildKeyboard(items))
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send shopping list to user {}", user.getTelegramChatId(), e);
        }
    }

    private String buildText(List<ShoppingItem> items) {
        if (items.isEmpty()) {
            return "🛒 *Список покупок*\n\n" + EMPTY_LIST_TEXT;
        }

        return "🛒 *Список покупок*\n\nНажми на позицию, чтобы отметить её купленной.";
    }

    private InlineKeyboardMarkup buildKeyboard(List<ShoppingItem> items) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        for (ShoppingItem item : items) {
            String box = item.isChecked() ? "☑" : "☐";
            // Целевой статус кодируем в callback'е по тому, что отрисовано сейчас:
            // ☐ → CHECK (target=true), ☑ → UNCHECK (target=false). Двойной тап =
            // один и тот же конечный статус, без дублей (issue #136).
            String action = item.isChecked() ? "UNCHECK" : "CHECK";

            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text(box + " " + item.getTitle())
                            .callbackData("SHOPPING:" + action + ":" + item.getId())
                            .build()
            )));
        }

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("➕ Добавить")
                        .callbackData("SHOPPING:ADD")
                        .build()
        )));

        if (items.stream().anyMatch(ShoppingItem::isChecked)) {
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text("🗑 Очистить отмеченные")
                            .callbackData("SHOPPING:CLEAR")
                            .build()
            )));
        }

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ Назад")
                        .callbackData("MENU:BACK")
                        .build()
        )));

        return InlineKeyboardMarkup.builder()
                .keyboard(rows)
                .build();
    }
}

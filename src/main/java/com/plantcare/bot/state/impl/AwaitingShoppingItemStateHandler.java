package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.ShoppingListMenuService;
import com.plantcare.bot.service.ShoppingListService;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Ждёт текст новой позиции списка покупок (issue #136).
 * На валидный текст — добавляет позицию, сбрасывает в IDLE и заново показывает список.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingShoppingItemStateHandler implements StateHandler {

    private final UserService userService;
    private final ShoppingListService shoppingListService;
    private final ShoppingListMenuService shoppingListMenuService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_SHOPPING_ITEM;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String title = update.getMessage().getText();

        try {
            shoppingListService.add(user, title);

            userService.resetToIdle(user);
            shoppingListMenuService.sendShoppingList(user, client);
        } catch (IllegalArgumentException e) {
            // Не сбрасываем состояние — даём юзеру повторить ввод.
            sendText(user, client, "❌ " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to add shopping item for user {}", user.getTelegramChatId(), e);
            sendText(user, client, "❌ Не получилось добавить позицию");
            userService.resetToIdle(user);
        }
    }

    private void sendText(User user, TelegramClient client, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(text)
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send message", e);
        }
    }
}

package com.plantcare.bot.state.interfaces;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

public interface StateHandler {
    void handle(User user, Update update, TelegramClient client);

    ConversationState getSupportedState();
}
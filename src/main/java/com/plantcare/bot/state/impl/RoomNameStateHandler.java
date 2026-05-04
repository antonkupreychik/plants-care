package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Component
@RequiredArgsConstructor
public class RoomNameStateHandler implements StateHandler {

    private final UserService userService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_ROOM_NAME;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        String roomName = update.getMessage().getText();
        userService.setStateData(user, "temp_room_name", roomName);
        userService.updateState(user, ConversationState.IDLE);
    }
}
package com.plantcare.bot.state;

import com.plantcare.bot.command.impl.SomethingWrongCommand;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class StateResolver {
    private final Map<ConversationState, StateHandler> stateHandlers;
    private final UserService userService;
    private final SomethingWrongCommand somethingWrongCommand;

    public StateResolver(List<StateHandler> handlers,
                         UserService userService,
                         SomethingWrongCommand somethingWrongCommand) {
        this.stateHandlers = handlers.stream()
                .collect(Collectors.toMap(StateHandler::getSupportedState, h -> h));
        this.userService = userService;
        this.somethingWrongCommand = somethingWrongCommand;
    }

    public void resolve(User user, Update update, TelegramClient client) {
        StateHandler handler = stateHandlers.get(user.getConversationState());

        if (handler == null) {
            log.error("No handler found for state: {}. Resetting to IDLE.", user.getConversationState());
            userService.resetToIdle(user);

            //when status resolver not found
            somethingWrongCommand.execute(update, client);
            return;
        }

        handler.handle(user, update, client);
    }
}
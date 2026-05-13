package com.plantcare.bot.beans;

import com.plantcare.bot.client.TelegramClientProvider;
import com.plantcare.bot.command.CommandContainer;
import com.plantcare.bot.command.impl.CancelCommand;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.MenuCallbackService;
import com.plantcare.bot.service.NotificationCallbackService;
import com.plantcare.bot.service.NotificationDigestCallbackService;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.state.StateResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Slf4j
@Component
public class PlantsCareBot implements LongPollingSingleThreadUpdateConsumer, TelegramClientProvider {

    private static final String UNKNOWN = "UNKNOWN";

    private static final String NOTIFICATION_CALLBACK_PREFIX = "v1:";
    private static final String DIGEST_CALLBACK_PREFIX = "digest:";
    private static final String MENU_CALLBACK_PREFIX = "MENU:";
    private static final String LOCATION_CALLBACK_PREFIX = "LOCATION:";
    private static final String PLANT_CALLBACK_PREFIX = "PLANT:";
    private static final String CALENDAR_CALLBACK_PREFIX = "cal:";

    private final TelegramClient telegramClient;
    private final CommandContainer commandContainer;
    private final UserService userService;
    private final StateResolver stateResolver;
    private final CancelCommand cancelCommand;
    private final NotificationCallbackService notificationCallbackService;
    private final NotificationDigestCallbackService notificationDigestCallbackService;
    private final MenuCallbackService menuCallbackService;

    public PlantsCareBot(
            @Value("${telegram.bot.token}") String botToken,
            CommandContainer commandContainer,
            UserService userService,
            StateResolver stateResolver,
            CancelCommand cancelCommand,
            NotificationCallbackService notificationCallbackService,
            NotificationDigestCallbackService notificationDigestCallbackService,
            MenuCallbackService menuCallbackService
    ) {
        this.telegramClient = new OkHttpTelegramClient(botToken);
        this.commandContainer = commandContainer;
        this.userService = userService;
        this.stateResolver = stateResolver;
        this.cancelCommand = cancelCommand;
        this.notificationCallbackService = notificationCallbackService;
        this.notificationDigestCallbackService = notificationDigestCallbackService;
        this.menuCallbackService = menuCallbackService;
    }

    @Override
    public TelegramClient getTelegramClient() {
        return telegramClient;
    }

    @Override
    public void consume(Update update) {
        Long chatId = getChatId(update);

        if (chatId == null) {
            return;
        }

        try {
            if (update.hasCallbackQuery()) {
                String data = update.getCallbackQuery().getData();

                if (data != null && data.startsWith(DIGEST_CALLBACK_PREFIX)) {
                    notificationDigestCallbackService.handleCallback(update.getCallbackQuery(), telegramClient);
                    return;
                }

                if (data != null && data.startsWith(NOTIFICATION_CALLBACK_PREFIX)) {
                    notificationCallbackService.handleCallback(update.getCallbackQuery(), telegramClient);
                    return;
                }

                if (data != null && (
                        data.startsWith(MENU_CALLBACK_PREFIX) ||
                                data.startsWith(LOCATION_CALLBACK_PREFIX) ||
                                data.startsWith(PLANT_CALLBACK_PREFIX) ||
                                data.startsWith(CALENDAR_CALLBACK_PREFIX)
                )) {
                    String userName = getUserName(update);
                    User user = userService.findOrCreate(chatId, userName);

                    menuCallbackService.handleCallback(update.getCallbackQuery(), telegramClient, user);
                    return;
                }
            }

            String userName = getUserName(update);
            User user = userService.findOrCreate(chatId, userName);

            if (update.hasMessage()
                    && update.getMessage().hasText()
                    && "/cancel".equalsIgnoreCase(update.getMessage().getText())) {
                cancelCommand.execute(update, telegramClient);
                return;
            }

            if (user.getConversationState() == ConversationState.IDLE) {
                if (!update.hasMessage() || !update.getMessage().hasText()) {
                    return;
                }

                String command = getCommandName(update.getMessage().getText());
                commandContainer.retrieveCommand(command).execute(update, telegramClient);
            } else {
                stateResolver.resolve(user, update, telegramClient);
            }
        } catch (Exception e) {
            log.error("Failed to consume update for chatId={}", chatId, e);
        }
    }

    private Long getChatId(Update update) {
        if (update.hasMessage()) {
            return update.getMessage().getChatId();
        }

        if (update.hasCallbackQuery()) {
            return update.getCallbackQuery().getMessage().getChatId();
        }

        return null;
    }

    private String getUserName(Update update) {
        if (update.hasMessage() && update.getMessage().getFrom() != null) {
            return update.getMessage().getFrom().getUserName();
        }

        if (update.hasCallbackQuery() && update.getCallbackQuery().getFrom() != null) {
            return update.getCallbackQuery().getFrom().getUserName();
        }

        return "unknown";
    }

    private String getCommandName(String text) {
        if (text == null || text.isBlank()) {
            return UNKNOWN;
        }

        return text.trim().split("\\s+")[0].toLowerCase();
    }
}
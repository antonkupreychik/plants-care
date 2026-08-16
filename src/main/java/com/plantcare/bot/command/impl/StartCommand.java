package com.plantcare.bot.command.impl;

import com.plantcare.bot.command.interfaces.BotCommand;
import com.plantcare.bot.service.LocationSharingMenuService;
import com.plantcare.bot.service.TelegramAuthLinkService;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.LocationSharingService;
import com.plantcare.core.service.MessageService;
import com.plantcare.core.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartCommand implements BotCommand {

    private static final String START_GREETING_SHOWN = "start_greeting_shown";

    private final UserService userService;
    private final MenuCommand menuCommand;
    private final MessageService messageService;
    private final LocationSharingService locationSharingService;
    private final TelegramAuthLinkService telegramAuthLinkService;

    @Override
    public String getCommandName() {
        return "/start";
    }

    @Override
    public void execute(Update update, TelegramClient client) {
        Long chatId = update.getMessage().getChatId();

        User user = userService.findByChatId(chatId)
                .orElseThrow(() -> new IllegalStateException("User not found"));

        // Вход в приложение (issue #318): deep link https://t.me/<bot>?start=auth_<sessionId>.
        // chat_id берём из самого update (не из клиента) — это и есть доверенная привязка.
        // Не показываем меню/приветствие: пользователь пришёл только за кодом входа.
        String authSessionId = telegramAuthLinkService.extractSessionId(extractStartPayload(update));
        if (authSessionId != null) {
            telegramAuthLinkService.handleAuthLink(authSessionId, chatId, client);
            return;
        }

        // Совместный уход (issue #77): deep link t.me/<bot>?start=invite_<token>.
        // Telegram кладёт payload вторым токеном текста: "/start invite_abc123".
        String invitePayload = extractInvitePayload(update);
        if (invitePayload != null) {
            sendInviteResult(invitePayload, user, chatId, client);
            // показываем меню, чтобы приглашённый сразу попал в бота
            userService.setStateData(user, START_GREETING_SHOWN, "true");
            menuCommand.execute(update, client);
            return;
        }

        if (!hasGreetingBeenShown(user)) {
            sendGreeting(chatId, client);
            userService.setStateData(user, START_GREETING_SHOWN, "true");
        }

        menuCommand.execute(update, client);
    }

    /**
     * Достаёт payload (второй токен) из {@code /start <payload>}.
     * Возвращает {@code null}, если это обычный {@code /start} без payload.
     */
    private String extractStartPayload(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return null;
        }

        String[] parts = update.getMessage().getText().trim().split("\\s+", 2);
        if (parts.length < 2) {
            return null;
        }

        String payload = parts[1].trim();
        return payload.isBlank() ? null : payload;
    }

    /**
     * Достаёт токен приглашения из payload {@code /start invite_<token>}.
     * Возвращает {@code null}, если это обычный {@code /start} без приглашения.
     */
    private String extractInvitePayload(Update update) {
        String payload = extractStartPayload(update);
        if (payload == null || !payload.startsWith(LocationSharingMenuService.INVITE_PAYLOAD_PREFIX)) {
            return null;
        }

        String token = payload.substring(LocationSharingMenuService.INVITE_PAYLOAD_PREFIX.length());
        return token.isBlank() ? null : token;
    }

    private void sendInviteResult(String token, User user, Long chatId, TelegramClient client) {
        LocationSharingService.AcceptResult result = locationSharingService.acceptInvite(token, user);

        String text = switch (result.status()) {
            case ACCEPTED -> "🤝 Вас пригласили ухаживать за растениями в «"
                    + result.locationName() + "». Теперь вы видите одни и те же растения и задачи.";
            case OWN_INVITE -> "Это ваша собственная комната «" + result.locationName()
                    + "» — приглашать себя не нужно.";
            case ALREADY_USED -> "Эта ссылка-приглашение уже была использована. Попроси новую.";
            case EXPIRED -> "Срок действия ссылки-приглашения истёк. Попроси новую.";
            case NOT_FOUND -> "Ссылка-приглашение недействительна.";
        };

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send invite acceptance message", e);
        }
    }

    private boolean hasGreetingBeenShown(User user) {
        return user.getStateData() != null
                && "true".equals(user.getStateData().get(START_GREETING_SHOWN));
    }

    private void sendGreeting(Long chatId, TelegramClient client) {
        SendMessage greeting = SendMessage.builder()
                .chatId(chatId.toString())
                .text(messageService.get(chatId, "command.start.greeting"))
                .build();

        try {
            client.execute(greeting);
        } catch (TelegramApiException e) {
            log.error("Failed to send greeting in /start", e);
        }
    }
}

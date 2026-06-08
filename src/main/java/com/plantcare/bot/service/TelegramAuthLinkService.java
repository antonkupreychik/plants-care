package com.plantcare.bot.service;

import com.plantcare.api.auth.service.TelegramAuthService;
import com.plantcare.core.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Optional;

/**
 * Issue #318: бот-сторона входа существующих Telegram-юзеров через код.
 *
 * <p>Тонкий мост между Telegram-слоем ({@code /start auth_<sessionId>}) и api-сервисом
 * входа: берёт {@code chat_id} ИЗ Telegram-update (передаётся вызывающим из
 * {@code update.getMessage().getChatId()}, не из клиентского тела), просит api
 * привязать код к сессии и шлёт код в чат существующим {@code SendMessage}.
 *
 * <p>Бизнес-логику (генерация/привязка кода, TTL) держит {@link TelegramAuthService};
 * здесь — только формирование и отправка сообщения (граница Telegram-слоя).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramAuthLinkService {

    /** Префикс payload {@code /start auth_<sessionId>}. */
    public static final String AUTH_PAYLOAD_PREFIX = "auth_";

    private final TelegramAuthService telegramAuthService;
    private final MessageService messageService;

    /**
     * Достаёт {@code sessionId} из payload {@code auth_<sessionId>}.
     * Возвращает {@code null}, если это не auth-диплинк.
     */
    public String extractSessionId(String payload) {
        if (payload == null || !payload.startsWith(AUTH_PAYLOAD_PREFIX)) {
            return null;
        }
        String sessionId = payload.substring(AUTH_PAYLOAD_PREFIX.length()).trim();
        return sessionId.isBlank() ? null : sessionId;
    }

    /**
     * Привязывает код к сессии и отправляет его в чат.
     *
     * @param sessionId      идентификатор сессии из deep link
     * @param chatId         chat_id ИЗ Telegram-update (источник правды по привязке)
     * @param client         Telegram-клиент для отправки
     */
    public void handleAuthLink(String sessionId, Long chatId, TelegramClient client) {
        Optional<String> code = telegramAuthService.bindCode(sessionId, chatId);

        String text = code
                .map(c -> messageService.get(chatId, "command.start.auth_code", c))
                .orElse(messageService.get(chatId, "command.start.auth_expired"));

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send Telegram auth code message for chatId={}", chatId, e);
        }
    }
}

package com.plantcare.admin.users.service;

import com.plantcare.admin.users.repository.AdminUserActionRepository;
import com.plantcare.admin.users.dto.SendMessageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Instant;

@Service
@Slf4j
public class AdminUserActionService {

    private final AdminUserActionRepository repository;
    private final ObjectProvider<TelegramClient> telegramClientProvider;

    public AdminUserActionService(
            AdminUserActionRepository repository,
            @Qualifier("adminTelegramClient") ObjectProvider<TelegramClient> telegramClientProvider) {
        this.repository = repository;
        this.telegramClientProvider = telegramClientProvider;
    }

    @Transactional
    public void resetState(long userId, String adminName) {
        int updated = repository.resetState(userId);
        if (updated == 0) throw notFound(userId);
        log.info("Admin action RESET_STATE: user_id={}, admin={}", userId, adminName);
    }

    @Transactional
    public boolean toggleBlock(long userId, String adminName) {
        var info = repository.findBasic(userId).orElseThrow(() -> notFound(userId));
        boolean newBlocked = !info.blocked();
        repository.setBlocked(userId, newBlocked);
        log.info("Admin action TOGGLE_BLOCK: user_id={}, new_value={}, admin={}",
                userId, newBlocked, adminName);
        return newBlocked;
    }

    @Transactional
    public Instant pause(long userId, Instant until, String adminName) {
        if (until == null || until.isBefore(Instant.now())) {
            throw new IllegalArgumentException("Срок паузы должен быть в будущем");
        }
        repository.findBasic(userId).orElseThrow(() -> notFound(userId));
        repository.setPausedUntil(userId, until);
        log.info("Admin action PAUSE: user_id={}, until={}, admin={}", userId, until, adminName);
        return until;
    }

    @Transactional
    public void unpause(long userId, String adminName) {
        repository.findBasic(userId).orElseThrow(() -> notFound(userId));
        repository.setPausedUntil(userId, null);
        log.info("Admin action UNPAUSE: user_id={}, admin={}", userId, adminName);
    }

    public SendMessageResult sendMessage(long userId, String text, String adminName) {
        if (text == null || text.isBlank()) return SendMessageResult.fail("Текст пустой");
        if (text.length() > 4000) return SendMessageResult.fail("Текст длиннее 4000 символов");

        var infoOpt = repository.findBasic(userId);
        if (infoOpt.isEmpty()) return SendMessageResult.fail("Юзер не найден");
        var info = infoOpt.get();
        if (info.blocked()) {
            return SendMessageResult.fail("Юзер заблокирован — отправка не имеет смысла");
        }

        TelegramClient client = telegramClientProvider.getIfAvailable();
        if (client == null) {
            log.error("Admin SEND_MESSAGE: TelegramClient bean missing — telegram.bot.token не задан?");
            return SendMessageResult.fail("Telegram client не настроен");
        }

        try {
            client.execute(SendMessage.builder()
                    .chatId(info.telegramChatId())
                    .text(text)
                    .build());
            log.info("Admin action SEND_MESSAGE: user_id={}, len={}, admin={}",
                    userId, text.length(), adminName);
            return SendMessageResult.ok();
        } catch (TelegramApiException e) {
            log.warn("Admin SEND_MESSAGE failed: user_id={}, error={}", userId, e.getMessage());
            return SendMessageResult.fail(e.getMessage());
        }
    }

    private static ResponseStatusException notFound(long userId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + userId);
    }
}

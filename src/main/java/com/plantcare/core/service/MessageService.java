package com.plantcare.core.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Резолвер локализованных строк для Telegram-слоя.
 *
 * <p>Сейчас локаль всегда {@code ru}. Перегрузки с {@code chatId} оставлены как точка расширения
 * для будущего выбора локали по пользователю (issue #20 — follow-up).
 */
@Service
@RequiredArgsConstructor
public class MessageService {

    private static final Locale DEFAULT_LOCALE = Locale.of("ru");

    private final MessageSource messageSource;

    public String get(String key) {
        return messageSource.getMessage(key, null, DEFAULT_LOCALE);
    }

    public String get(String key, Object... args) {
        return messageSource.getMessage(key, args, DEFAULT_LOCALE);
    }

    public String get(Long chatId, String key) {
        return get(key);
    }

    public String get(Long chatId, String key, Object... args) {
        return get(key, args);
    }
}

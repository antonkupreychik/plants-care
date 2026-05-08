package com.plantcare.bot.client;

import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Предоставляет доступ к TelegramClient для сервисов,
 * которым нужно отправлять сообщения вне контекста обработки Update
 * (например, шедулер уведомлений).
 */
public interface TelegramClientProvider {
    TelegramClient getTelegramClient();
}
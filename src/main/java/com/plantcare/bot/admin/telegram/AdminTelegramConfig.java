package com.plantcare.bot.admin.telegram;

import com.plantcare.bot.admin.config.AdminSecurityConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Отдельный TelegramClient для админских действий.
 * Не пересекается с PlantsCareBot — у того свой клиент в long-polling.
 * Создаётся только если включена админка И задан telegram.bot.token.
 */
@Configuration
@ConditionalOnExpression(AdminSecurityConfig.ADMIN_ENABLED_EXPR)
public class AdminTelegramConfig {

    @Bean(name = "adminTelegramClient")
    @ConditionalOnProperty(name = "telegram.bot.token")
    public TelegramClient adminTelegramClient(@Value("${telegram.bot.token}") String token) {
        return new OkHttpTelegramClient(token);
    }
}

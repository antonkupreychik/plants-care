package com.plantcare.bot.config;

import com.plantcare.bot.beans.PlantsCareBot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

@Configuration
public class TelegramBotConfig {

    @Bean
    @ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
    public TelegramBotsLongPollingApplication telegramBotsApplication(
            @Value("${telegram.bot.token}") String botToken,
            PlantsCareBot plantsCareBot) throws Exception {
        var app = new TelegramBotsLongPollingApplication();
        app.registerBot(botToken, plantsCareBot);
        return app;
    }
}
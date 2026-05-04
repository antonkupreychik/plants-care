package com.plantcare.bot.config;

import com.plantcare.bot.beans.PlantsCareBot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

@Configuration
public class TelegramBotConfig {

    @Bean
    public TelegramBotsLongPollingApplication telegramBotsApplication(
            @Value("${telegram.bot.token}") String botToken,
            PlantsCareBot plantsCareBot) throws Exception {

        TelegramBotsLongPollingApplication botsApplication = new TelegramBotsLongPollingApplication();
        botsApplication.registerBot(botToken, plantsCareBot);
        return botsApplication;
    }
}
package com.plantcare.bot;

import com.plantcare.bot.config.CalendarProperties;
import com.plantcare.bot.config.SpeciesProperties;
import com.plantcare.bot.config.TelegramRateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({SpeciesProperties.class, CalendarProperties.class, TelegramRateLimitProperties.class})
public class PlantsCareApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlantsCareApplication.class, args);
    }
}

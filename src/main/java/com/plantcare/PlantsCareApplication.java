package com.plantcare;

import com.plantcare.api.ratelimit.ApiRateLimitProperties;
import com.plantcare.core.config.CalendarProperties;
import com.plantcare.core.config.HealthScoreProperties;
import com.plantcare.core.config.SpeciesProperties;
import com.plantcare.bot.config.TelegramRateLimitProperties;
import com.plantcare.core.config.TransplantSuggestionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({SpeciesProperties.class, CalendarProperties.class, HealthScoreProperties.class, TelegramRateLimitProperties.class, TransplantSuggestionProperties.class, ApiRateLimitProperties.class})
public class PlantsCareApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlantsCareApplication.class, args);
    }
}

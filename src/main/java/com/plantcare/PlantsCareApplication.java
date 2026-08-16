package com.plantcare;

import com.plantcare.api.ratelimit.ApiRateLimitProperties;
import com.plantcare.core.config.CalendarProperties;
import com.plantcare.core.config.HealthScoreProperties;
import com.plantcare.core.config.S3StorageProperties;
import com.plantcare.core.config.SpeciesProperties;
import com.plantcare.bot.config.TelegramRateLimitProperties;
import com.plantcare.core.config.TransplantSuggestionProperties;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

// Issue #279, эпик #277 фаза 1: defaultLockAtMostFor = самый короткий период задач (1 мин).
// При сбое инстанса лок освободится за это время, и следующий тик сможет взять его.
@SpringBootApplication
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT1M")
@EnableConfigurationProperties({SpeciesProperties.class, CalendarProperties.class, HealthScoreProperties.class, TelegramRateLimitProperties.class, TransplantSuggestionProperties.class, ApiRateLimitProperties.class, S3StorageProperties.class})
public class PlantsCareApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlantsCareApplication.class, args);
    }
}

package com.plantcare.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Инжектируемый Clock-бин — единственный разрешённый способ получить текущее время.
 * Никогда не используй Instant.now() / LocalDateTime.now() в продакшен-коде напрямую.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

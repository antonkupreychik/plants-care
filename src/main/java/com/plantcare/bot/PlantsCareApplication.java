package com.plantcare.bot;

import com.plantcare.bot.config.CalendarProperties;
import com.plantcare.bot.config.SpeciesProperties;
import com.plantcare.bot.config.SpeciesProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({SpeciesProperties.class, CalendarProperties.class})
public class PlantsCareApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlantsCareApplication.class, args);
    }

    /**
     * Issue #79: единый источник времени для сервисов, чтобы тесты могли
     * подменять Clock. Все продакшен-вычисления времени должны идти через {@code clock.instant()}.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

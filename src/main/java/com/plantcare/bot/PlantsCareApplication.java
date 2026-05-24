package com.plantcare.bot;

import com.plantcare.bot.config.SpeciesProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(SpeciesProperties.class)
public class PlantsCareApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlantsCareApplication.class, args);
    }

    /**
     * Системные часы. Бин — чтобы шедулеры и сервисы, завязанные на «сейчас»
     * (отпуск, акклиматизация и т.п.), могли подменить Clock в тестах.
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}

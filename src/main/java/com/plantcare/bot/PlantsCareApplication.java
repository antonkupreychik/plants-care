package com.plantcare.bot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.plantcare.bot.config.SpeciesProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(SpeciesProperties.class)
public class PlantsCareApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlantsCareApplication.class, args);
    }
}

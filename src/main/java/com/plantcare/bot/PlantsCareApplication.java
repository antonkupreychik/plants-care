package com.plantcare.bot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PlantsCareApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlantsCareApplication.class, args);
    }
}

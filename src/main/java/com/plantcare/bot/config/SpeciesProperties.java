package com.plantcare.bot.config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@AllArgsConstructor
@ConfigurationProperties(prefix = "app.species")
public class SpeciesProperties {
    private final int searchLimit;
}
package com.plantcare.bot.weather.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Минимальный кусок ответа Open-Meteo {@code /v1/forecast?hourly=relative_humidity_2m}.
 * Игнорируем всё остальное; если API расширится — нам всё равно.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenMeteoResponse(
        String timezone,
        Hourly hourly
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hourly(
            /** ISO-8601 локальные timestamps (в timezone из верхнего поля), без TZ-offset'а. */
            List<String> time,
            /** Целочисленные значения относительной влажности 0..100 для каждого часа. */
            @JsonProperty("relative_humidity_2m") List<Integer> relativeHumidity2m
    ) {}
}

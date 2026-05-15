package com.plantcare.bot.weather.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * RestClient для походов в Open-Meteo (issue #69). Отдельный bean с
 * короткими таймаутами — нельзя ронять основной флоу бота из-за зависшего
 * external HTTP'а. Connect/read 5 секунд каждый, после — наружу прокидываем
 * exception, {@code WeatherService} ловит и возвращает {@code Optional.empty()}.
 */
@Configuration
public class WeatherConfig {

    @Bean
    public RestClient openMeteoRestClient(
            @Value("${weather.open-meteo.base-url:https://api.open-meteo.com/v1}") String baseUrl,
            @Value("${weather.open-meteo.timeout-seconds:5}") int timeoutSeconds
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("Accept", "application/json")
                .build();
    }
}

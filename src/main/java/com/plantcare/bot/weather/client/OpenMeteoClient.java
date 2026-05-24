package com.plantcare.bot.weather.client;

import com.plantcare.bot.weather.dto.OpenMeteoResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * Тонкий wrapper над Open-Meteo /v1/forecast (issue #69).
 *
 * <p>Никаких retry — это не критичный путь. Любой failure → {@link Optional#empty()},
 * caller ({@code WeatherService}) сам решает что делать. Логируем как WARN, чтобы
 * не засорять ERROR при кратковременных проблемах сети.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenMeteoClient {

    private final RestClient openMeteoRestClient;

    public Optional<OpenMeteoResponse> fetchHourlyHumidity(double lat, double lon) {
        // Issue #114: НЕ ставим тег layer=weather на общий scope. Клиент вызывается
        // из shared-потока @Scheduled (через WeatherService) и сам Sentry никогда не
        // зовёт (любой сбой → WARN + Optional.empty()). Глобальный setTag здесь
        // загрязнял бы scope тика шедулера тегом weather (см. issue #114, blocking).
        try {
            OpenMeteoResponse response = openMeteoRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/forecast")
                            .queryParam("latitude", lat)
                            .queryParam("longitude", lon)
                            .queryParam("hourly", "relative_humidity_2m")
                            .queryParam("timezone", "auto")
                            .build())
                    .retrieve()
                    .body(OpenMeteoResponse.class);
            if (response == null
                    || response.hourly() == null
                    || response.hourly().time() == null
                    || response.hourly().relativeHumidity2m() == null) {
                log.warn("Open-Meteo returned malformed body for lat={}, lon={}", lat, lon);
                return Optional.empty();
            }
            return Optional.of(response);
        } catch (Exception e) {
            // Любой сетевой/парсинговый сбой — не наша проблема, фича не критическая.
            log.warn("Open-Meteo call failed for lat={}, lon={}: {}", lat, lon, e.getMessage());
            return Optional.empty();
        }
    }
}

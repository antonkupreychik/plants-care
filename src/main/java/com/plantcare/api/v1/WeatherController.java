package com.plantcare.api.v1;

import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.generated.WeatherApi;
import com.plantcare.api.generated.model.WeatherSnapshotDto;
import com.plantcare.core.domain.User;
import com.plantcare.core.weather.dto.HumidityInfo;
import com.plantcare.core.weather.service.WeatherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;

/**
 * REST API для снепшота погоды (mobile gap G4, issue #69).
 *
 * <p>Тонкая обёртка над {@link WeatherService#getCurrentHumidity(User)}: то, что
 * раньше использовалось только Telegram-ботом, выставляется мобильному клиенту.
 * Если погода не настроена или источник недоступен — отдаём {@code available=false}
 * (не ошибка, клиент скрывает строку погоды).
 *
 * <p>Документация и mapping живут в сгенерированном {@link WeatherApi}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class WeatherController implements WeatherApi {

    private final WeatherService weatherService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public WeatherSnapshotDto getWeatherSnapshot() {
        User user = currentUserProvider.currentUser();
        return weatherService.getCurrentHumidity(user)
                .map(WeatherController::toDto)
                .orElseGet(() -> new WeatherSnapshotDto(false));
    }

    private static WeatherSnapshotDto toDto(HumidityInfo info) {
        return new WeatherSnapshotDto(true)
                .humidityPercent(info.humidityPercent())
                .recommendation(WeatherSnapshotDto.RecommendationEnum.fromValue(info.recommendation().name()))
                .fetchedAt(info.fetchedAt() != null ? info.fetchedAt().atOffset(ZoneOffset.UTC) : null)
                .fromCache(info.fromCache());
    }
}

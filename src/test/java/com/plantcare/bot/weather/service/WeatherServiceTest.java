package com.plantcare.bot.weather.service;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.repository.UserRepository;
import com.plantcare.bot.weather.client.OpenMeteoClient;
import com.plantcare.bot.weather.dto.HumidityInfo;
import com.plantcare.bot.weather.dto.OpenMeteoResponse;
import com.plantcare.bot.weather.dto.WeatherRecommendation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WeatherService — issue #69")
class WeatherServiceTest {

    @Mock private OpenMeteoClient client;
    @Mock private UserRepository userRepository;

    @InjectMocks
    private WeatherService service;

    private User user;

    @BeforeEach
    void setUp() {
        // @Value не подставляется в @InjectMocks — заводим вручную дефолты.
        ReflectionTestUtils.setField(service, "cacheMinutes", 60L);
        ReflectionTestUtils.setField(service, "thresholdDeferOk", 80);
        ReflectionTestUtils.setField(service, "thresholdDoNotDefer", 35);

        user = User.builder()
                .telegramChatId(100L)
                .timezone("Europe/Riga")
                .weatherEnabled(true)
                .weatherLat(56.95)
                .weatherLon(24.10)
                .build();
    }

    @Nested
    @DisplayName("getCurrentHumidity — guards")
    class Guards {

        @Test
        @DisplayName("null user → empty")
        void shouldHandleNullUser() {
            assertThat(service.getCurrentHumidity(null)).isEmpty();
            verify(client, never()).fetchHourlyHumidity(anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("Погода выключена → empty, без HTTP")
        void shouldSkipWhenDisabled() {
            user.setWeatherEnabled(false);

            assertThat(service.getCurrentHumidity(user)).isEmpty();
            verify(client, never()).fetchHourlyHumidity(anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("Локация не задана → empty, без HTTP")
        void shouldSkipWhenNoLocation() {
            user.setWeatherLat(null);

            assertThat(service.getCurrentHumidity(user)).isEmpty();
            verify(client, never()).fetchHourlyHumidity(anyDouble(), anyDouble());
        }
    }

    @Nested
    @DisplayName("Кеш")
    class Cache {

        @Test
        @DisplayName("Last fetch < 60 мин назад → отдаём из кеша, HTTP не зовётся")
        void shouldUseFreshCache() {
            user.setWeatherLastFetchAt(LocalDateTime.now().minusMinutes(5));
            user.setWeatherLastRh(82);

            Optional<HumidityInfo> result = service.getCurrentHumidity(user);

            assertThat(result).isPresent();
            assertThat(result.get().humidityPercent()).isEqualTo(82);
            assertThat(result.get().recommendation()).isEqualTo(WeatherRecommendation.DEFER_OK);
            assertThat(result.get().fromCache()).isTrue();
            verify(client, never()).fetchHourlyHumidity(anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("Last fetch > 60 мин → идём в HTTP, обновляем кеш")
        void shouldRefetchWhenStale() {
            user.setWeatherLastFetchAt(LocalDateTime.now().minusMinutes(90));
            user.setWeatherLastRh(40);
            stubFreshResponseFromApi(75);

            Optional<HumidityInfo> result = service.getCurrentHumidity(user);

            assertThat(result).isPresent();
            assertThat(result.get().humidityPercent()).isEqualTo(75);
            assertThat(result.get().fromCache()).isFalse();
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("Кеш пустой (first call) → идём в HTTP")
        void shouldRefetchOnFirstCall() {
            // user has no last_fetch_at
            stubFreshResponseFromApi(50);

            Optional<HumidityInfo> result = service.getCurrentHumidity(user);

            assertThat(result).isPresent();
            verify(userRepository).save(user);
        }
    }

    @Nested
    @DisplayName("Отказоустойчивость")
    class Resilience {

        @Test
        @DisplayName("Open-Meteo не вернул ответ → empty, кеш не трогается")
        void shouldReturnEmptyOnApiFailure() {
            when(client.fetchHourlyHumidity(anyDouble(), anyDouble())).thenReturn(Optional.empty());

            assertThat(service.getCurrentHumidity(user)).isEmpty();
            verify(userRepository, never()).save(user);
        }

        @Test
        @DisplayName("Open-Meteo вернул, но без матча часа → empty, кеш не трогается")
        void shouldReturnEmptyWhenNoMatchingHour() {
            // Прислать ответ с timestamp далеко в прошлом и без будущего
            OpenMeteoResponse resp = new OpenMeteoResponse(
                    "Europe/Riga",
                    new OpenMeteoResponse.Hourly(
                            List.of("2000-01-01T00:00", "2000-01-01T01:00"),
                            List.of(50, 60)));
            when(client.fetchHourlyHumidity(anyDouble(), anyDouble())).thenReturn(Optional.of(resp));

            assertThat(service.getCurrentHumidity(user)).isEmpty();
            verify(userRepository, never()).save(user);
        }
    }

    @Nested
    @DisplayName("Пороги интерпретации (issue AC)")
    class Thresholds {

        @ParameterizedTest(name = "RH={0}% → {1}")
        @CsvSource({
                "80, DEFER_OK",      // нижняя граница «можно отложить»
                "95, DEFER_OK",
                "50, NEUTRAL",       // нейтрально
                "36, NEUTRAL",       // только выше «не откладывать»
                "35, DO_NOT_DEFER",  // верхняя граница «лучше не откладывать»
                "0,  DO_NOT_DEFER"
        })
        @DisplayName("AC thresholds: ≥80 DEFER_OK, ≤35 DO_NOT_DEFER, иначе NEUTRAL")
        void shouldMapThresholds(int rh, WeatherRecommendation expected) {
            assertThat(service.recommend(rh)).isEqualTo(expected);
        }
    }

    /** Помощник: имитировать ответ API, где есть час, ближайший к now. */
    private void stubFreshResponseFromApi(int rhValue) {
        ZoneId zone = ZoneId.of("Europe/Riga");
        ZonedDateTime now = ZonedDateTime.now(zone).withMinute(0).withSecond(0).withNano(0);
        String hourNow = now.toLocalDateTime().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
        OpenMeteoResponse resp = new OpenMeteoResponse(
                zone.getId(),
                new OpenMeteoResponse.Hourly(List.of(hourNow), List.of(rhValue)));
        when(client.fetchHourlyHumidity(anyDouble(), anyDouble())).thenReturn(Optional.of(resp));
    }
}

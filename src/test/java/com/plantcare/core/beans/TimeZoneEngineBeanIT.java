package com.plantcare.core.beans;

import com.plantcare.bot.support.IntegrationTestBase;
import net.iakovlev.timeshape.TimeZoneEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест, гарантирующий, что реальный {@link TimeZoneEngine} корректно
 * загружается и резолвит координаты в не-UTC таймзону (требование CLAUDE.md, issue #239).
 *
 * <p>Зависит от основного контекста приложения ({@link IntegrationTestBase}), в котором
 * {@code TimeZoneEngine} регистрируется как ленивый бин. Этот тест первым обращается к
 * движку — тем самым инициируя загрузку гео-датасета ровно в одном из контекстов прогона.
 */
@DisplayName("TimeZoneEngineBean — реальный движок резолвит не-UTC координаты")
class TimeZoneEngineBeanIT extends IntegrationTestBase {

    @Autowired
    private TimeZoneEngine timeZoneEngine;

    @Test
    @DisplayName("Координаты Москвы → Europe/Moscow (UTC+3, не UTC)")
    void should_resolve_moscow_coordinates_to_europe_moscow_timezone() {
        // arrange — координаты центра Москвы
        double lat = 55.7558;
        double lon = 37.6173;

        // act
        Optional<ZoneId> result = timeZoneEngine.query(lat, lon);

        // assert
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("Europe/Moscow");
    }

    @Test
    @DisplayName("Координаты Алматы → Asia/Almaty (UTC+5, не UTC)")
    void should_resolve_almaty_coordinates_to_asia_almaty_timezone() {
        // arrange — координаты Алматы
        double lat = 43.2220;
        double lon = 76.8512;

        // act
        Optional<ZoneId> result = timeZoneEngine.query(lat, lon);

        // assert
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("Asia/Almaty");
    }
}

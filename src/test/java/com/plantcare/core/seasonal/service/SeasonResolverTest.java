package com.plantcare.core.seasonal.service;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.Season;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SeasonResolver — определение сезона (issue #67)")
class SeasonResolverTest {

    private SeasonResolver resolver;
    private User user;

    @BeforeEach
    void setUp() {
        resolver = new SeasonResolver();
        user = User.builder()
                .telegramChatId(100L)
                .timezone("Europe/Minsk")
                .summerStartMmdd(401)
                .winterStartMmdd(1001)
                .build();
    }

    @Nested
    @DisplayName("classify (pure function)")
    class Classify {

        @ParameterizedTest(name = "MMDD={0} с границами {1}..{2} → {3}")
        @CsvSource({
                // Дефолтные границы: лето 401..1001
                "401, 401, 1001, SUMMER",   // граница лета — лето
                "501, 401, 1001, SUMMER",   // 1 мая
                "715, 401, 1001, SUMMER",   // середина июля
                "930, 401, 1001, SUMMER",   // последний день лета (30 сентября)
                "1001, 401, 1001, WINTER",  // граница зимы — зима
                "1215, 401, 1001, WINTER",  // декабрь
                "101, 401, 1001, WINTER",   // январь — зима
                "331, 401, 1001, WINTER",   // 31 марта — последний день зимы
                "201, 401, 1001, WINTER",   // 1 февраля — определённо зима

                // Wrap-around (например южное полушарие — лето пересекает новый год)
                "1101, 1001, 401, SUMMER",  // лето с октября по март, ноябрь → лето
                "101, 1001, 401, SUMMER",   // январь — тоже лето
                "401, 1001, 401, WINTER",   // граница зимы
                "601, 1001, 401, WINTER",   // июнь — зима
        })
        @DisplayName("Pure classify учитывает обычный и wrap-around случаи")
        void shouldClassifyByMmdd(int mmdd, int summerStart, int winterStart,
                                  Season expected) {
            assertThat(resolver.classify(mmdd, summerStart, winterStart))
                    .isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("seasonAt (с User + TZ)")
    class SeasonAtWithTz {

        @Test
        @DisplayName("Europe/Minsk, июль → SUMMER")
        void julyIsSummerInMinsk() {
            ZonedDateTime now = ZonedDateTime.of(
                    2026, 7, 15, 12, 0, 0, 0, ZoneId.of("Europe/Minsk"));
            assertThat(resolver.seasonAt(user, now)).isEqualTo(Season.SUMMER);
        }

        @Test
        @DisplayName("Europe/Minsk, декабрь → WINTER")
        void decemberIsWinterInMinsk() {
            ZonedDateTime now = ZonedDateTime.of(
                    2026, 12, 15, 12, 0, 0, 0, ZoneId.of("Europe/Minsk"));
            assertThat(resolver.seasonAt(user, now)).isEqualTo(Season.WINTER);
        }

        @Test
        @DisplayName("Январь по UTC, но юзер в Asia/Tokyo (UTC+9) — дата та же")
        void shouldUseUserTzNotServerTz() {
            // 1 января 23:00 UTC = 2 января 08:00 в Tokyo. Январь — зима в обеих.
            user.setTimezone("Asia/Tokyo");
            ZonedDateTime nowUtc = ZonedDateTime.of(
                    2026, 1, 1, 23, 0, 0, 0, ZoneId.of("UTC"));
            assertThat(resolver.seasonAt(user, nowUtc)).isEqualTo(Season.WINTER);
        }

        @Test
        @DisplayName("Граничный случай: ровно начало лета (1 апреля) → SUMMER")
        void summerStartBoundary() {
            ZonedDateTime exact = ZonedDateTime.of(
                    2026, 4, 1, 0, 0, 0, 0, ZoneId.of("Europe/Minsk"));
            assertThat(resolver.seasonAt(user, exact)).isEqualTo(Season.SUMMER);
        }

        @Test
        @DisplayName("Граничный случай: ровно начало зимы (1 октября) → WINTER")
        void winterStartBoundary() {
            ZonedDateTime exact = ZonedDateTime.of(
                    2026, 10, 1, 0, 0, 0, 0, ZoneId.of("Europe/Minsk"));
            assertThat(resolver.seasonAt(user, exact)).isEqualTo(Season.WINTER);
        }
    }

    @Test
    @DisplayName("currentSeason использует ZonedDateTime.now() — smoke test")
    void currentSeasonReturnsNonNull() {
        Season s = resolver.currentSeason(user);
        assertThat(s).isIn(Season.SUMMER, Season.WINTER);
    }
}

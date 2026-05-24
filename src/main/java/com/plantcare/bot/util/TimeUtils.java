package com.plantcare.bot.util;

import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Утилитный класс для вычислений, связанных со временем и таймзонами.
 *
 * <p>Все методы принимают инжектируемый {@link Clock} — никаких вызовов
 * {@code LocalDate.now()} / {@code Instant.now()} без Clock не допускается.
 */
@Slf4j
public final class TimeUtils {

    private TimeUtils() {
    }

    /**
     * Конец текущего дня (23:59:59) в UTC, вычисленный относительно таймзоны пользователя.
     *
     * @param timezone строка таймзоны (например, "Europe/Moscow", "Asia/Almaty")
     * @param clock    инжектируемый Clock-бин
     * @return конец сегодняшнего дня в UTC
     */
    public static LocalDateTime endOfTodayInUtc(String timezone, Clock clock) {
        ZoneId userZone = safeZone(timezone);
        LocalDate todayInUserTz = clock.instant().atZone(userZone).toLocalDate();
        ZonedDateTime endOfDay = todayInUserTz
                .atTime(LocalTime.of(23, 59, 59))
                .atZone(userZone);
        return endOfDay.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * Безопасное получение {@link ZoneId} по строке: при неверном значении
     * логирует предупреждение и возвращает UTC.
     *
     * @param tz строка таймзоны
     * @return {@link ZoneId}, никогда не null
     */
    public static ZoneId safeZone(String tz) {
        if (tz == null || tz.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            log.warn("Invalid timezone '{}' — falling back to UTC", tz);
            return ZoneOffset.UTC;
        }
    }
}

package com.plantcare.core.util;

import com.plantcare.core.domain.User;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Утилиты для конвертации хранимых в UTC дат/времён в локальную TZ юзера
 * для отображения в боте.
 *
 * <p>Проблема, которую решает: все {@link LocalDateTime} в проекте создаются
 * через {@code LocalDateTime.now()} на сервере (UTC), а пишутся в БД как
 * naive TIMESTAMP. При отображении юзеру через {@code .toLocalDate()} мы
 * получаем «дату по UTC», а не по его часовому поясу. Для юзеров в +3..+5 это
 * приводит к ситуации «сейчас 02:00 по Москве, бот пишет вчерашнюю дату».
 *
 * <p>Этот хелпер централизует корректную конвертацию: UTC → user TZ → LocalDate.
 */
public final class TimezoneSupport {

    private TimezoneSupport() {}

    /**
     * Парсит TZ юзера, fallback на UTC при null/пусто/некорректной зоне.
     */
    public static ZoneId zoneOf(User user) {
        if (user == null) return ZoneOffset.UTC;
        String tzId = user.getTimezone();
        if (tzId == null || tzId.isBlank()) return ZoneOffset.UTC;
        try {
            return ZoneId.of(tzId);
        } catch (Exception e) {
            return ZoneOffset.UTC;
        }
    }

    /**
     * UTC-{@link LocalDateTime} → {@link LocalDate} в TZ юзера.
     * Возвращает {@code null}, если входной datetime {@code null}.
     */
    public static LocalDate dateInUserZone(LocalDateTime utc, User user) {
        if (utc == null) return null;
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(zoneOf(user)).toLocalDate();
    }

    /** «Сегодня» в TZ юзера — для сравнения с результатом {@link #dateInUserZone}. */
    public static LocalDate todayInUserZone(User user) {
        return LocalDate.now(zoneOf(user));
    }
}

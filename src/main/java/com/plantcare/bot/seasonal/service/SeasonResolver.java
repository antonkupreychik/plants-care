package com.plantcare.bot.seasonal.service;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.Season;
import com.plantcare.bot.util.TimezoneSupport;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Определяет текущий сезон для конкретного юзера на основе его настроек
 * границ сезонов (issue #67).
 *
 * <p>Границы хранятся как MMDD-целые числа: 401 = 1 апреля, 1001 = 1 октября.
 * Это позволяет легко сравнивать даты числовым операторами без парсинга.
 *
 * <p>«Лето» — диапазон от {@code summerStartMmdd} (включительно) до
 * {@code winterStartMmdd} (исключительно). Всё остальное — «зима». Это
 * автоматически обрабатывает зимний wrap-around через новый год: если
 * сегодня 15 января, MMDD = 115, что меньше 401 → не лето → зима. ✓
 *
 * <p>Сезон определяется в TZ юзера, а не в TZ сервера: иначе бы граница
 * сезона срабатывала в разное время для разных юзеров (и тестируемость
 * страдала бы).
 */
@Service
public class SeasonResolver {

    /** Текущий сезон для юзера в его TZ. */
    public Season currentSeason(User user) {
        return seasonAt(user, ZonedDateTime.now(TimezoneSupport.zoneOf(user)));
    }

    /** Сезон на произвольный момент (для тестов и projection-планирования). */
    public Season seasonAt(User user, ZonedDateTime when) {
        ZoneId zone = TimezoneSupport.zoneOf(user);
        LocalDate localDate = when.withZoneSameInstant(zone).toLocalDate();
        int mmdd = localDate.getMonthValue() * 100 + localDate.getDayOfMonth();
        return classify(mmdd, user.getSummerStartMmdd(), user.getWinterStartMmdd());
    }

    /**
     * Чистая функция для тестируемости — без User. Принимает MMDD текущей даты
     * и MMDD границ сезонов.
     *
     * <p>Алгоритм: «лето» — это полуинтервал [summerStart, winterStart).
     * Любое значение MMDD вне этого интервала → «зима». Для дефолтных границ
     * (summer=401, winter=1001) лето идёт с 1 апреля включительно по
     * 30 сентября включительно, что точно соответствует AC.
     */
    public Season classify(int currentMmdd, int summerStartMmdd, int winterStartMmdd) {
        // Защита от настроек «start лета > start зимы» (т.е. лето пересекает
        // новый год — нетипично, но возможно для южного полушария).
        // Тогда «лето» — это [summerStart, конец года] ∪ [начало года, winterStart).
        if (summerStartMmdd <= winterStartMmdd) {
            return (currentMmdd >= summerStartMmdd && currentMmdd < winterStartMmdd)
                    ? Season.SUMMER
                    : Season.WINTER;
        } else {
            // Wrap-around: лето с октября по март.
            return (currentMmdd >= summerStartMmdd || currentMmdd < winterStartMmdd)
                    ? Season.SUMMER
                    : Season.WINTER;
        }
    }
}

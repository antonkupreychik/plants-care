package com.plantcare.bot.weather.service;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.repository.UserRepository;
import com.plantcare.bot.util.TimezoneSupport;
import com.plantcare.bot.weather.client.OpenMeteoClient;
import com.plantcare.bot.weather.dto.HumidityInfo;
import com.plantcare.bot.weather.dto.OpenMeteoResponse;
import com.plantcare.bot.weather.dto.WeatherRecommendation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * Главная точка входа для погодных рекомендаций (issue #69).
 *
 * <p>Поведение {@link #getCurrentHumidity(User)}:
 * <ol>
 *   <li>Если погода у юзера выключена / нет координат → {@link Optional#empty()}.</li>
 *   <li>Если есть свежий кеш в БД (last_fetch_at &lt; 60 мин назад) →
 *       строим {@link HumidityInfo} из {@code weather_last_rh} без HTTP-запроса.</li>
 *   <li>Иначе — ходим в Open-Meteo, ищем час, ближайший к «сейчас» в TZ юзера,
 *       обновляем кеш в БД и возвращаем результат.</li>
 *   <li>Если HTTP-запрос упал — {@link Optional#empty()}. Caller просто не покажет
 *       погодную подсказку; это не ошибка приложения.</li>
 * </ol>
 *
 * <p>Пороги интерпретации задаются через property для тестов; дефолты соответствуют issue:
 * RH ≥ 80% → DEFER_OK; RH ≤ 35% → DO_NOT_DEFER; иначе → NEUTRAL.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WeatherService {

    /** Open-Meteo возвращает «time»: «2026-05-15T14:00» без секунд и TZ. */
    private static final DateTimeFormatter OPEN_METEO_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final OpenMeteoClient client;
    private final UserRepository userRepository;

    /** Минимальный интервал между внешними HTTP-запросами на одного юзера, в минутах. */
    @Value("${weather.cache-minutes:60}")
    private long cacheMinutes;

    /** Порог «можно отложить» (RH в %, ≥). */
    @Value("${weather.threshold-defer-ok:80}")
    private int thresholdDeferOk;

    /** Порог «лучше не откладывать» (RH в %, ≤). */
    @Value("${weather.threshold-do-not-defer:35}")
    private int thresholdDoNotDefer;

    /**
     * Текущая влажность для юзера. Не делает побочных действий, если погода
     * не настроена. Безопасно вызывать из любого места.
     */
    public Optional<HumidityInfo> getCurrentHumidity(User user) {
        if (user == null || !user.isWeatherUsable()) {
            return Optional.empty();
        }

        // Кеш: если ходили недавно, отдаём last_rh без HTTP.
        if (user.getWeatherLastFetchAt() != null && user.getWeatherLastRh() != null) {
            Duration sinceLast = Duration.between(user.getWeatherLastFetchAt(), LocalDateTime.now());
            if (sinceLast.toMinutes() < cacheMinutes) {
                int rh = user.getWeatherLastRh();
                return Optional.of(new HumidityInfo(
                        rh, recommend(rh), user.getWeatherLastFetchAt(), /* fromCache */ true));
            }
        }

        // Cache miss — идём в Open-Meteo.
        Optional<OpenMeteoResponse> response = client.fetchHourlyHumidity(
                user.getWeatherLat(), user.getWeatherLon());
        if (response.isEmpty()) {
            return Optional.empty();
        }

        Optional<Integer> rhOpt = pickHourly(response.get(), user);
        if (rhOpt.isEmpty()) {
            log.warn("Open-Meteo response had no matching hour for user {}",
                    user.getTelegramChatId());
            return Optional.empty();
        }
        int rh = clampPercent(rhOpt.get());

        updateCache(user, rh);

        return Optional.of(new HumidityInfo(
                rh, recommend(rh), LocalDateTime.now(), /* fromCache */ false));
    }

    /**
     * Атомарное обновление кеш-полей. Транзакция своя — вызывается из горячих
     * путей (рендеринг меню), нельзя завязать на их транзакции.
     */
    @Transactional
    public void updateCache(User user, int rh) {
        user.setWeatherLastFetchAt(LocalDateTime.now());
        user.setWeatherLastRh(rh);
        userRepository.save(user);
    }

    /**
     * Выбор значения RH из массива hourly, ближайшего к текущему часу в TZ юзера.
     * Open-Meteo с {@code timezone=auto} возвращает локальные timestamps;
     * найти текущий час — это просто индекс с совпадающим «yyyy-MM-dd'T'HH:00».
     */
    Optional<Integer> pickHourly(OpenMeteoResponse response, User user) {
        ZoneId userZone = TimezoneSupport.zoneOf(user);
        // У Open-Meteo есть свой timezone в ответе; если он отличается от user.zone
        // (например, юзер не задал TZ корректно), всё равно ориентируемся на ответ.
        ZoneId zone;
        try {
            zone = response.timezone() == null || response.timezone().isBlank()
                    ? userZone
                    : ZoneId.of(response.timezone());
        } catch (Exception e) {
            zone = userZone;
        }
        ZonedDateTime nowAtZone = ZonedDateTime.now(zone).withMinute(0).withSecond(0).withNano(0);
        String needle = nowAtZone.toLocalDateTime().format(OPEN_METEO_TIME);

        List<String> times = response.hourly().time();
        List<Integer> rhs = response.hourly().relativeHumidity2m();
        if (times.size() != rhs.size()) {
            return Optional.empty();
        }

        // Сначала ищем точное совпадение часа; если его нет — берём первый ≥ now.
        for (int i = 0; i < times.size(); i++) {
            if (needle.equals(times.get(i))) {
                Integer v = rhs.get(i);
                return v == null ? Optional.empty() : Optional.of(v);
            }
        }
        for (int i = 0; i < times.size(); i++) {
            try {
                if (!LocalDateTime.parse(times.get(i), OPEN_METEO_TIME).isBefore(nowAtZone.toLocalDateTime())) {
                    Integer v = rhs.get(i);
                    return v == null ? Optional.empty() : Optional.of(v);
                }
            } catch (DateTimeParseException ignored) {
                // edge-case с битой строкой — пропускаем
            }
        }
        return Optional.empty();
    }

    WeatherRecommendation recommend(int rh) {
        if (rh >= thresholdDeferOk)     return WeatherRecommendation.DEFER_OK;
        if (rh <= thresholdDoNotDefer)  return WeatherRecommendation.DO_NOT_DEFER;
        return WeatherRecommendation.NEUTRAL;
    }

    private static int clampPercent(int rh) {
        if (rh < 0)   return 0;
        if (rh > 100) return 100;
        return rh;
    }
}

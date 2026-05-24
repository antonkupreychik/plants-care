package com.plantcare.bot.weather.dto;

import java.time.LocalDateTime;

/**
 * Снепшот текущей влажности за окном для рендеринга в боте (issue #69).
 *
 * @param humidityPercent относительная влажность 0..100
 * @param recommendation  что бот советует — производное от {@code humidityPercent}
 * @param fetchedAt       когда последний раз ходили в API (для отладки/диагностики)
 * @param fromCache       {@code true}, если значение прилетело из БД-кеша (60-мин окно),
 *                        {@code false} — если только что вытащили из Open-Meteo
 */
public record HumidityInfo(
        int humidityPercent,
        WeatherRecommendation recommendation,
        LocalDateTime fetchedAt,
        boolean fromCache
) {
    /**
     * Готовая короткая строка для подсказки в меню или push'е, например:
     *   «🌦 Влажность 82% — можно отложить, если земля ещё влажная».
     * Для NEUTRAL мы намеренно не давим лишним текстом, возвращаем краткое.
     */
    public String renderLine() {
        return switch (recommendation) {
            case DEFER_OK -> "🌦 Влажность %d%% — можно отложить, если земля ещё влажная"
                    .formatted(humidityPercent);
            case DO_NOT_DEFER -> "🌦 Влажность %d%% — лучше не откладывать полив"
                    .formatted(humidityPercent);
            case NEUTRAL -> "🌦 Влажность %d%% — нормальная".formatted(humidityPercent);
        };
    }
}

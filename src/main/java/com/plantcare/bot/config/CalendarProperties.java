package com.plantcare.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Issue #79: настройки публичной .ics подписки.
 *
 * <ul>
 *   <li>{@code baseUrl} — внешний хост приложения для построения ссылки вида
 *       {@code https://<host>/calendar/{token}.ics}. На прод задаётся через
 *       env {@code CALENDAR_BASE_URL}.</li>
 *   <li>{@code eventWindowDays} — окно вперёд от {@code now()}, в пределах которого
 *       расписания превращаются в события календаря. По AC issue #79 — 90 дней.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.calendar")
public record CalendarProperties(String baseUrl, int eventWindowDays) {

    public CalendarProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://example.com";
        }
        if (eventWindowDays <= 0) {
            eventWindowDays = 90;
        }
    }
}

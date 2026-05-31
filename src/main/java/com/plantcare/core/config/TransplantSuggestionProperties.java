package com.plantcare.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Issue #141: настройки подсказки расходников перед предстоящей пересадкой.
 *
 * <p>«Предстоящая пересадка» вычисляется как дата последней TRANSPLANT-записи
 * в журнале растения ({@code plant_events}, issue #76) плюс {@link #intervalMonths()}.
 * Если эта дата попадает в окно ближайших {@link #horizonDays()} дней (в TZ
 * юзера), шедулер шлёт мягкую нотификацию с предложением добавить
 * {@link #supplies()} в список покупок (issue #136).
 *
 * <ul>
 *   <li>{@code enabled} — мастер-переключатель фичи (default {@code true});</li>
 *   <li>{@code horizonDays} — за сколько дней до предстоящей пересадки подсказывать
 *       (default 14);</li>
 *   <li>{@code intervalMonths} — типовой интервал между пересадками (default 12);</li>
 *   <li>{@code supplies} — какие позиции предлагать в список покупок
 *       (default {@code ["Грунт", "Дренаж"]});</li>
 *   <li>{@code messageTemplate} — шаблон текста нотификации; первый {@code %s} —
 *       имя растения, второй — перечисление расходников.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "plants.transplant-suggestion")
public record TransplantSuggestionProperties(
        boolean enabled,
        int horizonDays,
        int intervalMonths,
        List<String> supplies,
        String messageTemplate
) {

    private static final List<String> DEFAULT_SUPPLIES = List.of("Грунт", "Дренаж");
    private static final String DEFAULT_MESSAGE_TEMPLATE =
            "🪴 Растению «%s» скоро пересадка — добавить расходники (%s) в список покупок?";

    public TransplantSuggestionProperties {
        if (horizonDays <= 0) {
            horizonDays = 14;
        }
        if (intervalMonths <= 0) {
            intervalMonths = 12;
        }
        if (supplies == null || supplies.isEmpty()) {
            supplies = DEFAULT_SUPPLIES;
        } else {
            supplies = List.copyOf(supplies);
        }
        if (messageTemplate == null || messageTemplate.isBlank()) {
            messageTemplate = DEFAULT_MESSAGE_TEMPLATE;
        }
    }
}

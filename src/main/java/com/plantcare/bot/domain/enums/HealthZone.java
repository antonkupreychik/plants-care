package com.plantcare.bot.domain.enums;

/**
 * Зона здоровья растения (issue #138). Маппинг числового health-score → цветовая
 * зона для подсветки в карточке и сводки в списке.
 *
 * <p>Конкретные пороги задаются в {@code app.health-score} (см.
 * {@code HealthScoreProperties}) — enum несёт только семантику и эмодзи.
 */
public enum HealthZone {

    GREEN("🟢"),
    YELLOW("🟡"),
    RED("🔴");

    private final String emoji;

    HealthZone(String emoji) {
        this.emoji = emoji;
    }

    public String emoji() {
        return emoji;
    }
}

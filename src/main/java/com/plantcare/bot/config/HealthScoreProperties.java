package com.plantcare.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Issue #138: коэффициенты формулы health-score растения и пороги зон.
 *
 * <p>Формула прозрачна и линейна:
 * <pre>
 *   score = round(onTimePct * onTimeWeight)
 *         + (есть фото  ? photoBonus : 0)
 *         + (есть заметки ? notesBonus : 0)
 * </pre>
 * затем клампится в [0, 100].
 *
 * <ul>
 *   <li>{@code onTimeWeight} — доля 100-балльной шкалы, отдаваемая под % on-time за
 *       30 дней. По умолчанию 0.8 → идеальная серия (100% on-time) даёт 80 баллов,
 *       оставшиеся 20 — на бонусы.</li>
 *   <li>{@code photoBonus} — баллы за наличие фото (issue #24). По умолчанию 10.</li>
 *   <li>{@code notesBonus} — баллы за наличие заметок (issue #25). По умолчанию 10.</li>
 *   <li>{@code greenThreshold} — score ≥ него → зелёная зона 🟢 (по умолчанию 80).</li>
 *   <li>{@code yellowThreshold} — score ≥ него (но &lt; green) → жёлтая зона 🟡
 *       (по умолчанию 50); ниже — красная 🔴.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.health-score")
public record HealthScoreProperties(
        double onTimeWeight,
        int photoBonus,
        int notesBonus,
        int greenThreshold,
        int yellowThreshold
) {

    public HealthScoreProperties {
        if (onTimeWeight <= 0) {
            onTimeWeight = 0.8;
        }
        if (photoBonus < 0) {
            photoBonus = 10;
        }
        if (notesBonus < 0) {
            notesBonus = 10;
        }
        if (greenThreshold <= 0) {
            greenThreshold = 80;
        }
        if (yellowThreshold <= 0) {
            yellowThreshold = 50;
        }
        if (yellowThreshold >= greenThreshold) {
            greenThreshold = 80;
            yellowThreshold = 50;
        }
    }
}

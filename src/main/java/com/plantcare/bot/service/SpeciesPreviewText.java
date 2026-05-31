package com.plantcare.bot.service;

import com.plantcare.core.domain.enums.LightPreference;

/**
 * Presentation-хелпер превью-сообщения вида в flow добавления растения (#9).
 * Единый источник текста для двух хендлеров (выбор из популярных и из поиска),
 * чтобы не дублировать формат «✨ name / Полив... / 💡 ... / Тебе подходит?».
 */
public final class SpeciesPreviewText {

    private SpeciesPreviewText() {
    }

    /**
     * Собирает превью вида. Строка-рекомендация по освещению добавляется только
     * если {@code lightPreference != null}; иначе превью остаётся без неё.
     */
    public static String build(String speciesName, Integer wateringDays, LightPreference lightPreference) {
        var wateringText = wateringDays != null
                ? String.format("Полив: раз в %d дней", wateringDays)
                : "Полив: по необходимости";

        var sb = new StringBuilder();
        sb.append("✨ *").append(speciesName).append("*\n\n");
        sb.append(wateringText);

        var lightPhrase = LightPreferenceText.phrase(lightPreference);
        if (!lightPhrase.isBlank()) {
            sb.append("\n💡 Для этого вида лучше: ").append(lightPhrase);
        }

        sb.append("\n\nТебе подходит?");
        return sb.toString();
    }
}

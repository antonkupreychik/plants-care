package com.plantcare.bot.service;

import com.plantcare.core.domain.enums.LightPreference;

/**
 * Presentation-хелпер человекочитаемых фраз освещения по {@link LightPreference}.
 * Единый источник текста для рекомендации в flow добавления растения (#9)
 * и для строки в детальной карточке (#26), чтобы не было копипасты.
 * Presentation-логике не место в core-enum, поэтому фразы живут здесь, в bot-слое.
 */
public final class LightPreferenceText {

    private LightPreferenceText() {
    }

    /**
     * Человекочитаемая фраза освещения. Для {@code null} возвращает пустую строку,
     * чтобы вызывающий код не падал и мог безопасно проверить {@code isBlank()}.
     */
    public static String phrase(LightPreference preference) {
        if (preference == null) {
            return "";
        }
        return switch (preference) {
            case SHADE -> "тень";
            case PARTIAL -> "полутень, рассеянный свет";
            case BRIGHT -> "яркий рассеянный свет";
            case DIRECT -> "прямые солнечные лучи";
        };
    }
}

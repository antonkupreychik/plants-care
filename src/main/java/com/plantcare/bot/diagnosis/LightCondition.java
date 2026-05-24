package com.plantcare.bot.diagnosis;

import java.util.Arrays;

public enum LightCondition {
    DIRECT_SUN("direct_sun", "☀️ Прямое солнце"),
    BRIGHT_DIFFUSED("bright_diffused", "🌤 Яркий рассеянный свет"),
    LOW_LIGHT("low_light", "🌑 Мало света"),
    NOT_SURE("unknown", "❓ Не знаю");

    private final String code;
    private final String label;

    LightCondition(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static LightCondition fromCode(String code) {
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown light condition: " + code));
    }
}
package com.plantcare.bot.diagnosis;

import java.util.Arrays;

public enum WateringFrequency {
    MORE_THAN_USUAL("more", "💧 Чаще обычного"),
    AS_USUAL("usual", "✅ Как обычно"),
    LESS_THAN_USUAL("less", "🏜 Реже обычного"),
    NOT_SURE("unknown", "❓ Не знаю");

    private final String code;
    private final String label;

    WateringFrequency(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static WateringFrequency fromCode(String code) {
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown watering frequency: " + code));
    }
}
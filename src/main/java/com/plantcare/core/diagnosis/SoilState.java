package com.plantcare.core.diagnosis;

import java.util.Arrays;

public enum SoilState {
    WET("wet", "💦 Влажный/мокрый"),
    DRY("dry", "🏜 Сухой"),
    NORMAL("normal", "✅ Слегка влажный/обычный"),
    NOT_SURE("unknown", "❓ Не знаю");

    private final String code;
    private final String label;

    SoilState(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static SoilState fromCode(String code) {
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown soil state: " + code));
    }
}
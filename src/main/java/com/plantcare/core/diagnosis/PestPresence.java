package com.plantcare.core.diagnosis;

import java.util.Arrays;

public enum PestPresence {
    YES("yes", "🐛 Да, вижу вредителей/паутинку"),
    NO("no", "✅ Нет"),
    NOT_SURE("unknown", "❓ Не знаю");

    private final String code;
    private final String label;

    PestPresence(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static PestPresence fromCode(String code) {
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown pest presence: " + code));
    }
}
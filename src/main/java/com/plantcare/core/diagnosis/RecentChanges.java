package com.plantcare.core.diagnosis;

import java.util.Arrays;

public enum RecentChanges {
    MOVED_OR_REPOTTED("moved_or_repotted", "🔄 Переставляли/пересаживали"),
    NEW_PLANT("new_plant", "🆕 Растение недавно куплено"),
    NO_CHANGES("no_changes", "✅ Ничего не менялось"),
    NOT_SURE("unknown", "❓ Не знаю");

    private final String code;
    private final String label;

    RecentChanges(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static RecentChanges fromCode(String code) {
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown recent changes: " + code));
    }
}
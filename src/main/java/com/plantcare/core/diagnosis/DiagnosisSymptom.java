package com.plantcare.core.diagnosis;

import java.util.Arrays;

public enum DiagnosisSymptom {
    YELLOW_LEAVES("yellow_leaves", "🍂 Листья желтеют"),
    WILTING_LEAVES("wilting_leaves", "🥀 Листья вянут/опускаются"),
    BROWN_DRY_TIPS("brown_dry_tips", "🤎 Коричневые сухие кончики"),
    LEAF_SPOTS("leaf_spots", "🟤 Пятна на листьях"),
    PESTS_OR_WEB("pests_or_web", "🐛 Насекомые/паутинка"),
    OTHER("other", "❓ Другое");

    private final String code;
    private final String label;

    DiagnosisSymptom(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static DiagnosisSymptom fromCode(String code) {
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown diagnosis symptom: " + code));
    }
}
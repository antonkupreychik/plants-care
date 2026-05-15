package com.plantcare.bot.diagnosis;

public record DiagnosisAnswers(
        Long plantId,
        DiagnosisSymptom symptom,
        WateringFrequency wateringFrequency,
        SoilState soilState,
        LightCondition lightCondition,
        RecentChanges recentChanges,
        PestPresence pestPresence
) {

    public DiagnosisAnswers withPlantId(Long plantId) {
        return new DiagnosisAnswers(
                plantId,
                symptom,
                wateringFrequency,
                soilState,
                lightCondition,
                recentChanges,
                pestPresence
        );
    }

    public DiagnosisAnswers withSymptom(DiagnosisSymptom symptom) {
        return new DiagnosisAnswers(
                plantId,
                symptom,
                wateringFrequency,
                soilState,
                lightCondition,
                recentChanges,
                pestPresence
        );
    }

    public DiagnosisAnswers withWateringFrequency(WateringFrequency wateringFrequency) {
        return new DiagnosisAnswers(
                plantId,
                symptom,
                wateringFrequency,
                soilState,
                lightCondition,
                recentChanges,
                pestPresence
        );
    }

    public DiagnosisAnswers withSoilState(SoilState soilState) {
        return new DiagnosisAnswers(
                plantId,
                symptom,
                wateringFrequency,
                soilState,
                lightCondition,
                recentChanges,
                pestPresence
        );
    }

    public DiagnosisAnswers withLightCondition(LightCondition lightCondition) {
        return new DiagnosisAnswers(
                plantId,
                symptom,
                wateringFrequency,
                soilState,
                lightCondition,
                recentChanges,
                pestPresence
        );
    }

    public DiagnosisAnswers withRecentChanges(RecentChanges recentChanges) {
        return new DiagnosisAnswers(
                plantId,
                symptom,
                wateringFrequency,
                soilState,
                lightCondition,
                recentChanges,
                pestPresence
        );
    }

    public DiagnosisAnswers withPestPresence(PestPresence pestPresence) {
        return new DiagnosisAnswers(
                plantId,
                symptom,
                wateringFrequency,
                soilState,
                lightCondition,
                recentChanges,
                pestPresence
        );
    }

    public static DiagnosisAnswers empty(Long plantId) {
        return new DiagnosisAnswers(
                plantId,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
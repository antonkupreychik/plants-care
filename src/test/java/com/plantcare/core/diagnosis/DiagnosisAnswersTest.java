package com.plantcare.core.diagnosis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisAnswersTest {

    @Test
    void should_createEmptyAnswersWithOnlyPlantIdSet_when_emptyCalled() {
        DiagnosisAnswers answers = DiagnosisAnswers.empty(42L);

        assertThat(answers.plantId()).isEqualTo(42L);
        assertThat(answers.symptom()).isNull();
        assertThat(answers.wateringFrequency()).isNull();
        assertThat(answers.soilState()).isNull();
        assertThat(answers.lightCondition()).isNull();
        assertThat(answers.recentChanges()).isNull();
        assertThat(answers.pestPresence()).isNull();
    }

    @Test
    void should_replacePlantIdOnly_when_withPlantIdCalled() {
        DiagnosisAnswers base = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.YELLOW_LEAVES,
                WateringFrequency.AS_USUAL,
                SoilState.NORMAL,
                LightCondition.BRIGHT_DIFFUSED,
                RecentChanges.NO_CHANGES,
                PestPresence.NO
        );

        DiagnosisAnswers updated = base.withPlantId(99L);

        assertThat(updated.plantId()).isEqualTo(99L);
        assertThat(updated.symptom()).isEqualTo(DiagnosisSymptom.YELLOW_LEAVES);
        assertThat(updated.wateringFrequency()).isEqualTo(WateringFrequency.AS_USUAL);
        assertThat(updated.soilState()).isEqualTo(SoilState.NORMAL);
        assertThat(updated.lightCondition()).isEqualTo(LightCondition.BRIGHT_DIFFUSED);
        assertThat(updated.recentChanges()).isEqualTo(RecentChanges.NO_CHANGES);
        assertThat(updated.pestPresence()).isEqualTo(PestPresence.NO);
    }

    @Test
    void should_replaceSymptomOnly_when_withSymptomCalled() {
        DiagnosisAnswers base = DiagnosisAnswers.empty(1L);

        DiagnosisAnswers updated = base.withSymptom(DiagnosisSymptom.PESTS_OR_WEB);

        assertThat(updated.symptom()).isEqualTo(DiagnosisSymptom.PESTS_OR_WEB);
        assertThat(updated.plantId()).isEqualTo(1L);
        assertThat(updated.wateringFrequency()).isNull();
    }

    @Test
    void should_replaceWateringFrequencyOnly_when_withWateringFrequencyCalled() {
        DiagnosisAnswers base = DiagnosisAnswers.empty(1L);

        DiagnosisAnswers updated = base.withWateringFrequency(WateringFrequency.LESS_THAN_USUAL);

        assertThat(updated.wateringFrequency()).isEqualTo(WateringFrequency.LESS_THAN_USUAL);
        assertThat(updated.soilState()).isNull();
    }

    @Test
    void should_replaceSoilStateOnly_when_withSoilStateCalled() {
        DiagnosisAnswers base = DiagnosisAnswers.empty(1L);

        DiagnosisAnswers updated = base.withSoilState(SoilState.WET);

        assertThat(updated.soilState()).isEqualTo(SoilState.WET);
        assertThat(updated.lightCondition()).isNull();
    }

    @Test
    void should_replaceLightConditionOnly_when_withLightConditionCalled() {
        DiagnosisAnswers base = DiagnosisAnswers.empty(1L);

        DiagnosisAnswers updated = base.withLightCondition(LightCondition.DIRECT_SUN);

        assertThat(updated.lightCondition()).isEqualTo(LightCondition.DIRECT_SUN);
        assertThat(updated.recentChanges()).isNull();
    }

    @Test
    void should_replaceRecentChangesOnly_when_withRecentChangesCalled() {
        DiagnosisAnswers base = DiagnosisAnswers.empty(1L);

        DiagnosisAnswers updated = base.withRecentChanges(RecentChanges.NEW_PLANT);

        assertThat(updated.recentChanges()).isEqualTo(RecentChanges.NEW_PLANT);
        assertThat(updated.pestPresence()).isNull();
    }

    @Test
    void should_replacePestPresenceOnly_when_withPestPresenceCalled() {
        DiagnosisAnswers base = DiagnosisAnswers.empty(1L);

        DiagnosisAnswers updated = base.withPestPresence(PestPresence.YES);

        assertThat(updated.pestPresence()).isEqualTo(PestPresence.YES);
        assertThat(updated.symptom()).isNull();
    }
}

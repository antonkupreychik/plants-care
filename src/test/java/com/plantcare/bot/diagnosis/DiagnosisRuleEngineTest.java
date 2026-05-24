package com.plantcare.bot.diagnosis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisRuleEngineTest {

    private DiagnosisRuleEngine ruleEngine;

    @BeforeEach
    void setUp() {
        ruleEngine = new DiagnosisRuleEngine();
    }

    @Test
    void shouldDetectOverwateringRiskForYellowLeavesWetSoilAndFrequentWatering() {
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.YELLOW_LEAVES,
                WateringFrequency.MORE_THAN_USUAL,
                SoilState.WET,
                LightCondition.BRIGHT_DIFFUSED,
                RecentChanges.NO_CHANGES,
                PestPresence.NO
        );

        DiagnosisResult result = ruleEngine.diagnose(answers);

        assertThat(result.possibleCauses())
                .anyMatch(text -> text.contains("перелив") || text.contains("влаги"));
        assertThat(result.actionsNow())
                .anyMatch(text -> text.contains("полив") || text.contains("поддона"));
        assertThat(result.additionalChecks())
                .anyMatch(text -> text.contains("дренаж"));
    }

    @Test
    void shouldDetectDrynessForWiltingLeavesDrySoilAndLessWatering() {
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.WILTING_LEAVES,
                WateringFrequency.LESS_THAN_USUAL,
                SoilState.DRY,
                LightCondition.BRIGHT_DIFFUSED,
                RecentChanges.NO_CHANGES,
                PestPresence.NO
        );

        DiagnosisResult result = ruleEngine.diagnose(answers);

        assertThat(result.possibleCauses())
                .anyMatch(text -> text.contains("пересуш") || text.contains("сух"));
        assertThat(result.actionsNow())
                .anyMatch(text -> text.contains("Полей") || text.contains("грунт"));
    }

    @Test
    void shouldDetectSunStressForSpotsAndDirectSun() {
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.LEAF_SPOTS,
                WateringFrequency.AS_USUAL,
                SoilState.NORMAL,
                LightCondition.DIRECT_SUN,
                RecentChanges.NO_CHANGES,
                PestPresence.NO
        );

        DiagnosisResult result = ruleEngine.diagnose(answers);

        assertThat(result.possibleCauses())
                .anyMatch(text -> text.contains("ожог") || text.contains("солн"));
        assertThat(result.actionsNow())
                .anyMatch(text -> text.contains("рассеянный свет") || text.contains("Переставь"));
    }

    @Test
    void shouldDetectPestsWhenPestsAreVisible() {
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.PESTS_OR_WEB,
                WateringFrequency.NOT_SURE,
                SoilState.NOT_SURE,
                LightCondition.NOT_SURE,
                RecentChanges.NOT_SURE,
                PestPresence.YES
        );

        DiagnosisResult result = ruleEngine.diagnose(answers);

        assertThat(result.possibleCauses())
                .anyMatch(text -> text.contains("вредител"));
        assertThat(result.actionsNow())
                .anyMatch(text -> text.contains("Изолируй"));
        assertThat(result.additionalChecks())
                .anyMatch(text -> text.contains("нижнюю сторону") || text.contains("осмотр"));
    }

    @Test
    void shouldHandleOtherSymptomSafely() {
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.OTHER,
                WateringFrequency.NOT_SURE,
                SoilState.NOT_SURE,
                LightCondition.NOT_SURE,
                RecentChanges.NOT_SURE,
                PestPresence.NOT_SURE
        );

        DiagnosisResult result = ruleEngine.diagnose(answers);

        assertThat(result.possibleCauses()).isNotEmpty();
        assertThat(result.actionsNow()).isNotEmpty();
        assertThat(result.additionalChecks()).isNotEmpty();
    }

    @Test
    void shouldFallbackWhenAnswersAreNull() {
        DiagnosisResult result = ruleEngine.diagnose(null);

        assertThat(result.possibleCauses()).isNotEmpty();
        assertThat(result.actionsNow()).isNotEmpty();
        assertThat(result.additionalChecks()).isNotEmpty();
    }

    @Test
    void shouldFallbackWhenSymptomIsNull() {
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                null,
                null,
                null,
                null,
                null,
                null
        );

        DiagnosisResult result = ruleEngine.diagnose(answers);

        assertThat(result.possibleCauses()).isNotEmpty();
        assertThat(result.actionsNow()).isNotEmpty();
        assertThat(result.additionalChecks()).isNotEmpty();
    }
}
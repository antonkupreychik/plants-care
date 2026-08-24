package com.plantcare.core.diagnosis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Дополнительные ветки {@link DiagnosisRuleEngine}, не покрытые
 * {@link DiagnosisRuleEngineTest}: одиночные (не комбинированные) состояния
 * грунта/полива, свет без совпадающего симптома, недавние изменения и
 * раздельные плечи OR в правиле вредителей.
 */
class DiagnosisRuleEngineCoverageTest {

    private DiagnosisRuleEngine ruleEngine;

    @BeforeEach
    void setUp() {
        ruleEngine = new DiagnosisRuleEngine();
    }

    @Test
    void should_reportExcessMoisture_when_soilWetButWateringNotIncreased() {
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.YELLOW_LEAVES,
                WateringFrequency.AS_USUAL,
                SoilState.WET,
                LightCondition.BRIGHT_DIFFUSED,
                RecentChanges.NO_CHANGES,
                PestPresence.NO
        );

        DiagnosisResult result = ruleEngine.diagnose(answers);

        assertThat(result.possibleCauses())
                .anyMatch(text -> text.contains("избыток влаги") || text.contains("слабое просыхание"));
        assertThat(result.actionsNow())
                .anyMatch(text -> text.contains("Не поливай"));
        assertThat(result.possibleCauses())
                .noneMatch(text -> text.contains("риск перелива"));
    }

    @Test
    void should_reportDryEvaporation_when_soilDryButWateringNotDecreased() {
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.BROWN_DRY_TIPS,
                WateringFrequency.AS_USUAL,
                SoilState.DRY,
                LightCondition.BRIGHT_DIFFUSED,
                RecentChanges.NO_CHANGES,
                PestPresence.NO
        );

        DiagnosisResult result = ruleEngine.diagnose(answers);

        assertThat(result.possibleCauses())
                .anyMatch(text -> text.contains("пересушка") || text.contains("испарение"));
        assertThat(result.additionalChecks())
                .anyMatch(text -> text.contains("жарком солнце") || text.contains("отоплением"));
    }

    @Test
    void should_addNormalConditionsCheck_when_soilNormalAndWateringAsUsual() {
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.OTHER,
                WateringFrequency.AS_USUAL,
                SoilState.NORMAL,
                LightCondition.BRIGHT_DIFFUSED,
                RecentChanges.NO_CHANGES,
                PestPresence.NO
        );

        DiagnosisResult result = ruleEngine.diagnose(answers);

        assertThat(result.additionalChecks())
                .anyMatch(text -> text.contains("свет, вредителей и недавние изменения"));
    }

    @Test
    void should_reportLowLightStress_when_lowLightWithYellowLeaves() {
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.YELLOW_LEAVES,
                WateringFrequency.AS_USUAL,
                SoilState.NORMAL,
                LightCondition.LOW_LIGHT,
                RecentChanges.NO_CHANGES,
                PestPresence.NO
        );

        DiagnosisResult result = ruleEngine.diagnose(answers);

        assertThat(result.possibleCauses())
                .anyMatch(text -> text.contains("Недостаток света"));
        assertThat(result.additionalChecks())
                .anyMatch(text -> text.contains("вытягиваются"));
    }

    @Test
    void should_notAddSunStressCause_when_directSunWithUnrelatedSymptom() {
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.YELLOW_LEAVES,
                WateringFrequency.AS_USUAL,
                SoilState.NORMAL,
                LightCondition.DIRECT_SUN,
                RecentChanges.NO_CHANGES,
                PestPresence.NO
        );

        DiagnosisResult result = ruleEngine.diagnose(answers);

        assertThat(result.possibleCauses())
                .noneMatch(text -> text.contains("солнечный ожог") || text.contains("перегрев"));
    }

    @Test
    void should_notAddLowLightCause_when_lowLightWithUnrelatedSymptom() {
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.LEAF_SPOTS,
                WateringFrequency.AS_USUAL,
                SoilState.NORMAL,
                LightCondition.LOW_LIGHT,
                RecentChanges.NO_CHANGES,
                PestPresence.NO
        );

        DiagnosisResult result = ruleEngine.diagnose(answers);

        assertThat(result.possibleCauses())
                .noneMatch(text -> text.contains("Недостаток света"));
    }

    @Test
    void should_reportAdaptationStress_when_recentlyMovedOrRepotted() {
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.WILTING_LEAVES,
                WateringFrequency.AS_USUAL,
                SoilState.NORMAL,
                LightCondition.NOT_SURE,
                RecentChanges.MOVED_OR_REPOTTED,
                PestPresence.NO
        );

        DiagnosisResult result = ruleEngine.diagnose(answers);

        assertThat(result.possibleCauses())
                .anyMatch(text -> text.contains("перестановки или пересадки"));
        assertThat(result.actionsNow())
                .anyMatch(text -> text.contains("несколько условий одновременно"));
    }

    @Test
    void should_reportNewPlantAdaptation_when_recentlyAcquiredNewPlant() {
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.OTHER,
                WateringFrequency.AS_USUAL,
                SoilState.NORMAL,
                LightCondition.NOT_SURE,
                RecentChanges.NEW_PLANT,
                PestPresence.NO
        );

        DiagnosisResult result = ruleEngine.diagnose(answers);

        assertThat(result.possibleCauses())
                .anyMatch(text -> text.contains("Новое растение"));
        assertThat(result.actionsNow())
                .anyMatch(text -> text.contains("7–14 дней"));
    }

    @Test
    void should_reportPestRisk_when_pestPresenceYesButSymptomUnrelated() {
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.YELLOW_LEAVES,
                WateringFrequency.AS_USUAL,
                SoilState.NORMAL,
                LightCondition.NOT_SURE,
                RecentChanges.NO_CHANGES,
                PestPresence.YES
        );

        DiagnosisResult result = ruleEngine.diagnose(answers);

        assertThat(result.possibleCauses())
                .anyMatch(text -> text.contains("вредител"));
        assertThat(result.actionsNow())
                .anyMatch(text -> text.contains("Изолируй"));
    }

    @Test
    void should_reportPestRisk_when_symptomIsPestsOrWebButPestPresenceNo() {
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.PESTS_OR_WEB,
                WateringFrequency.AS_USUAL,
                SoilState.NORMAL,
                LightCondition.NOT_SURE,
                RecentChanges.NO_CHANGES,
                PestPresence.NO
        );

        DiagnosisResult result = ruleEngine.diagnose(answers);

        assertThat(result.possibleCauses())
                .anyMatch(text -> text.contains("вредител"));
    }

    @Test
    void should_notAddNormalConditionsCheck_when_soilNormalButWateringNotAsUsual() {
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.OTHER,
                WateringFrequency.MORE_THAN_USUAL,
                SoilState.NORMAL,
                LightCondition.BRIGHT_DIFFUSED,
                RecentChanges.NO_CHANGES,
                PestPresence.NO
        );

        DiagnosisResult result = ruleEngine.diagnose(answers);

        assertThat(result.additionalChecks())
                .noneMatch(text -> text.contains("свет, вредителей и недавние изменения"));
    }

    @Test
    void should_addSoilCheckupHint_when_wateringNotSureButSoilIsKnown() {
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.OTHER,
                WateringFrequency.NOT_SURE,
                SoilState.DRY,
                LightCondition.BRIGHT_DIFFUSED,
                RecentChanges.NO_CHANGES,
                PestPresence.NO
        );

        DiagnosisResult result = ruleEngine.diagnose(answers);

        assertThat(result.additionalChecks())
                .anyMatch(text -> text.contains("деревянной палочкой"));
    }

    @Test
    void should_reportSunStress_when_directSunWithBrownDryTips() {
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.BROWN_DRY_TIPS,
                WateringFrequency.AS_USUAL,
                SoilState.NORMAL,
                LightCondition.DIRECT_SUN,
                RecentChanges.NO_CHANGES,
                PestPresence.NO
        );

        DiagnosisResult result = ruleEngine.diagnose(answers);

        assertThat(result.possibleCauses())
                .anyMatch(text -> text.contains("солнечный ожог") || text.contains("перегрев"));
    }

    @Test
    void should_reportSunStress_when_directSunWithWiltingLeaves() {
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.WILTING_LEAVES,
                WateringFrequency.AS_USUAL,
                SoilState.NORMAL,
                LightCondition.DIRECT_SUN,
                RecentChanges.NO_CHANGES,
                PestPresence.NO
        );

        DiagnosisResult result = ruleEngine.diagnose(answers);

        assertThat(result.possibleCauses())
                .anyMatch(text -> text.contains("солнечный ожог") || text.contains("перегрев"));
    }

    @Test
    void should_reportLowLightStress_when_lowLightWithWiltingLeaves() {
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.WILTING_LEAVES,
                WateringFrequency.AS_USUAL,
                SoilState.NORMAL,
                LightCondition.LOW_LIGHT,
                RecentChanges.NO_CHANGES,
                PestPresence.NO
        );

        DiagnosisResult result = ruleEngine.diagnose(answers);

        assertThat(result.possibleCauses())
                .anyMatch(text -> text.contains("Недостаток света"));
    }

    @Test
    void should_addPestObservationCheck_when_pestPresenceNotSureAndSymptomUnrelated() {
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.YELLOW_LEAVES,
                WateringFrequency.AS_USUAL,
                SoilState.NORMAL,
                LightCondition.NOT_SURE,
                RecentChanges.NO_CHANGES,
                PestPresence.NOT_SURE
        );

        DiagnosisResult result = ruleEngine.diagnose(answers);

        assertThat(result.additionalChecks())
                .anyMatch(text -> text.contains("пазухи"));
        assertThat(result.possibleCauses())
                .noneMatch(text -> text.contains("Есть риск заражения"));
    }
}

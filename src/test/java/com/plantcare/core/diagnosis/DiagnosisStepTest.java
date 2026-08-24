package com.plantcare.core.diagnosis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisStepTest {

    @Test
    void should_returnAllStepsInWizardOrder_when_valuesCalled() {
        DiagnosisStep[] steps = DiagnosisStep.values();

        assertThat(steps).containsExactly(
                DiagnosisStep.DISCLAIMER,
                DiagnosisStep.SYMPTOM,
                DiagnosisStep.WATERING,
                DiagnosisStep.SOIL,
                DiagnosisStep.LIGHT,
                DiagnosisStep.RECENT_CHANGES,
                DiagnosisStep.PESTS,
                DiagnosisStep.RESULT,
                DiagnosisStep.WAITING_PHOTO
        );
    }

    @Test
    void should_resolveStepByName_when_valueOfCalledWithKnownName() {
        DiagnosisStep step = DiagnosisStep.valueOf("WATERING");

        assertThat(step).isEqualTo(DiagnosisStep.WATERING);
    }

    @Test
    void should_throwIllegalArgumentException_when_valueOfCalledWithUnknownName() {
        org.junit.jupiter.api.function.Executable call = () -> DiagnosisStep.valueOf("NOT_A_STEP");

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, call))
                .hasMessageContaining("NOT_A_STEP");
    }
}

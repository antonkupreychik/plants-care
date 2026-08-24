package com.plantcare.core.diagnosis;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DiagnosisSessionServiceTest {

    @Mock
    private UserService userService;

    private DiagnosisSessionService sessionService;

    private User user;

    @BeforeEach
    void setUp() {
        sessionService = new DiagnosisSessionService(userService);
        user = User.builder()
                .telegramChatId(100L)
                .timezone("UTC")
                .build();
    }

    @Test
    void should_switchUserToAwaitingDiagnosisAndSaveInitialStepAndPlantId_when_startCalled() {
        sessionService.start(user, 42L);

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_DIAGNOSIS);
        verify(userService).setStateData(user, DiagnosisSessionService.KEY_STEP, DiagnosisStep.DISCLAIMER.name());
        verify(userService).setStateData(user, DiagnosisSessionService.KEY_PLANT_ID, "42");
    }

    @Test
    void should_persistStepName_when_setStepCalled() {
        sessionService.setStep(user, DiagnosisStep.SOIL);

        verify(userService).setStateData(user, DiagnosisSessionService.KEY_STEP, "SOIL");
    }

    @Test
    void should_returnStoredStep_when_getStepCalledWithExistingKey() {
        user.setStateData(new HashMap<>(Map.of(DiagnosisSessionService.KEY_STEP, "WATERING")));

        DiagnosisStep step = sessionService.getStep(user);

        assertThat(step).isEqualTo(DiagnosisStep.WATERING);
    }

    @Test
    void should_defaultToDisclaimer_when_getStepCalledWithNoStoredStep() {
        user.setStateData(new HashMap<>());

        DiagnosisStep step = sessionService.getStep(user);

        assertThat(step).isEqualTo(DiagnosisStep.DISCLAIMER);
    }

    @Test
    void should_defaultToDisclaimer_when_getStepCalledWithNullStateData() {
        user.setStateData(null);

        DiagnosisStep step = sessionService.getStep(user);

        assertThat(step).isEqualTo(DiagnosisStep.DISCLAIMER);
    }

    @Test
    void should_returnParsedPlantId_when_getPlantIdCalledWithExistingKey() {
        user.setStateData(new HashMap<>(Map.of(DiagnosisSessionService.KEY_PLANT_ID, "77")));

        Optional<Long> plantId = sessionService.getPlantId(user);

        assertThat(plantId).contains(77L);
    }

    @Test
    void should_returnEmpty_when_getPlantIdCalledWithBlankStoredValue() {
        Map<String, Object> data = new HashMap<>();
        data.put(DiagnosisSessionService.KEY_PLANT_ID, "   ");
        user.setStateData(data);

        Optional<Long> plantId = sessionService.getPlantId(user);

        assertThat(plantId).isEmpty();
    }

    @Test
    void should_returnEmpty_when_getPlantIdCalledWithNoStateData() {
        user.setStateData(null);

        Optional<Long> plantId = sessionService.getPlantId(user);

        assertThat(plantId).isEmpty();
    }

    @Test
    void should_persistSymptomCode_when_saveSymptomCalled() {
        sessionService.saveSymptom(user, DiagnosisSymptom.LEAF_SPOTS);

        verify(userService).setStateData(user, DiagnosisSessionService.KEY_SYMPTOM, "leaf_spots");
    }

    @Test
    void should_persistWateringCode_when_saveWateringCalled() {
        sessionService.saveWatering(user, WateringFrequency.MORE_THAN_USUAL);

        verify(userService).setStateData(user, DiagnosisSessionService.KEY_WATERING, "more");
    }

    @Test
    void should_persistSoilCode_when_saveSoilCalled() {
        sessionService.saveSoil(user, SoilState.DRY);

        verify(userService).setStateData(user, DiagnosisSessionService.KEY_SOIL, "dry");
    }

    @Test
    void should_persistLightCode_when_saveLightCalled() {
        sessionService.saveLight(user, LightCondition.LOW_LIGHT);

        verify(userService).setStateData(user, DiagnosisSessionService.KEY_LIGHT, "low_light");
    }

    @Test
    void should_persistRecentChangesCode_when_saveRecentChangesCalled() {
        sessionService.saveRecentChanges(user, RecentChanges.MOVED_OR_REPOTTED);

        verify(userService).setStateData(
                user, DiagnosisSessionService.KEY_RECENT_CHANGES, "moved_or_repotted");
    }

    @Test
    void should_persistPestsCode_when_savePestsCalled() {
        sessionService.savePests(user, PestPresence.NOT_SURE);

        verify(userService).setStateData(user, DiagnosisSessionService.KEY_PESTS, "unknown");
    }

    @Test
    void should_buildFullAnswers_when_getAnswersCalledWithAllStateData() {
        Map<String, Object> data = new HashMap<>();
        data.put(DiagnosisSessionService.KEY_PLANT_ID, "5");
        data.put(DiagnosisSessionService.KEY_SYMPTOM, "yellow_leaves");
        data.put(DiagnosisSessionService.KEY_WATERING, "more");
        data.put(DiagnosisSessionService.KEY_SOIL, "wet");
        data.put(DiagnosisSessionService.KEY_LIGHT, "direct_sun");
        data.put(DiagnosisSessionService.KEY_RECENT_CHANGES, "new_plant");
        data.put(DiagnosisSessionService.KEY_PESTS, "yes");
        user.setStateData(data);

        DiagnosisAnswers answers = sessionService.getAnswers(user);

        assertThat(answers.plantId()).isEqualTo(5L);
        assertThat(answers.symptom()).isEqualTo(DiagnosisSymptom.YELLOW_LEAVES);
        assertThat(answers.wateringFrequency()).isEqualTo(WateringFrequency.MORE_THAN_USUAL);
        assertThat(answers.soilState()).isEqualTo(SoilState.WET);
        assertThat(answers.lightCondition()).isEqualTo(LightCondition.DIRECT_SUN);
        assertThat(answers.recentChanges()).isEqualTo(RecentChanges.NEW_PLANT);
        assertThat(answers.pestPresence()).isEqualTo(PestPresence.YES);
    }

    @Test
    void should_buildAllNullAnswersExceptPlantId_when_getAnswersCalledWithNoStateData() {
        user.setStateData(null);

        DiagnosisAnswers answers = sessionService.getAnswers(user);

        assertThat(answers.plantId()).isNull();
        assertThat(answers.symptom()).isNull();
        assertThat(answers.wateringFrequency()).isNull();
        assertThat(answers.soilState()).isNull();
        assertThat(answers.lightCondition()).isNull();
        assertThat(answers.recentChanges()).isNull();
        assertThat(answers.pestPresence()).isNull();
    }

    @Test
    void should_resetUserToIdle_when_finishCalled() {
        sessionService.finish(user);

        verify(userService).resetToIdle(user);
    }
}

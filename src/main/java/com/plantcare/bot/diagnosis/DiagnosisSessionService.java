package com.plantcare.bot.diagnosis;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DiagnosisSessionService {

    public static final String KEY_STEP = "diagnosis_step";
    public static final String KEY_PLANT_ID = "diagnosis_plant_id";
    public static final String KEY_SYMPTOM = "diagnosis_symptom";
    public static final String KEY_WATERING = "diagnosis_watering";
    public static final String KEY_SOIL = "diagnosis_soil";
    public static final String KEY_LIGHT = "diagnosis_light";
    public static final String KEY_RECENT_CHANGES = "diagnosis_recent_changes";
    public static final String KEY_PESTS = "diagnosis_pests";

    private final UserService userService;

    public void start(User user, Long plantId) {
        userService.updateState(user, ConversationState.AWAITING_PLANT_DIAGNOSIS);
        userService.setStateData(user, KEY_STEP, DiagnosisStep.DISCLAIMER.name());
        userService.setStateData(user, KEY_PLANT_ID, String.valueOf(plantId));
    }

    public void setStep(User user, DiagnosisStep step) {
        userService.setStateData(user, KEY_STEP, step.name());
    }

    public DiagnosisStep getStep(User user) {
        return get(user, KEY_STEP)
                .map(DiagnosisStep::valueOf)
                .orElse(DiagnosisStep.DISCLAIMER);
    }

    public Optional<Long> getPlantId(User user) {
        return get(user, KEY_PLANT_ID).map(Long::parseLong);
    }

    public void saveSymptom(User user, DiagnosisSymptom symptom) {
        userService.setStateData(user, KEY_SYMPTOM, symptom.code());
    }

    public void saveWatering(User user, WateringFrequency wateringFrequency) {
        userService.setStateData(user, KEY_WATERING, wateringFrequency.code());
    }

    public void saveSoil(User user, SoilState soilState) {
        userService.setStateData(user, KEY_SOIL, soilState.code());
    }

    public void saveLight(User user, LightCondition lightCondition) {
        userService.setStateData(user, KEY_LIGHT, lightCondition.code());
    }

    public void saveRecentChanges(User user, RecentChanges recentChanges) {
        userService.setStateData(user, KEY_RECENT_CHANGES, recentChanges.code());
    }

    public void savePests(User user, PestPresence pestPresence) {
        userService.setStateData(user, KEY_PESTS, pestPresence.code());
    }

    public DiagnosisAnswers getAnswers(User user) {
        Long plantId = getPlantId(user).orElse(null);

        DiagnosisSymptom symptom = get(user, KEY_SYMPTOM)
                .map(DiagnosisSymptom::fromCode)
                .orElse(null);

        WateringFrequency watering = get(user, KEY_WATERING)
                .map(WateringFrequency::fromCode)
                .orElse(null);

        SoilState soil = get(user, KEY_SOIL)
                .map(SoilState::fromCode)
                .orElse(null);

        LightCondition light = get(user, KEY_LIGHT)
                .map(LightCondition::fromCode)
                .orElse(null);

        RecentChanges recentChanges = get(user, KEY_RECENT_CHANGES)
                .map(RecentChanges::fromCode)
                .orElse(null);

        PestPresence pests = get(user, KEY_PESTS)
                .map(PestPresence::fromCode)
                .orElse(null);

        return new DiagnosisAnswers(
                plantId,
                symptom,
                watering,
                soil,
                light,
                recentChanges,
                pests
        );
    }

    public void finish(User user) {
        userService.resetToIdle(user);
    }

    private Optional<String> get(User user, String key) {
        Map<String, Object> data = user.getStateData();

        if (data == null) {
            return Optional.empty();
        }

        Object value = data.get(key);

        if (value == null) {
            return Optional.empty();
        }

        String stringValue = String.valueOf(value);

        if (stringValue.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(stringValue);
    }
}
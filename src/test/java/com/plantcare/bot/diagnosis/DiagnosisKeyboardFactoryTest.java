package com.plantcare.bot.diagnosis;

import com.plantcare.core.diagnosis.DiagnosisSymptom;
import com.plantcare.core.diagnosis.LightCondition;
import com.plantcare.core.diagnosis.PestPresence;
import com.plantcare.core.diagnosis.RecentChanges;
import com.plantcare.core.diagnosis.SoilState;
import com.plantcare.core.diagnosis.WateringFrequency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisKeyboardFactoryTest {

    private DiagnosisKeyboardFactory factory;

    @BeforeEach
    void setUp() {
        factory = new DiagnosisKeyboardFactory();
    }

    private List<String> texts(InlineKeyboardMarkup markup) {
        return markup.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getText)
                .toList();
    }

    private List<String> callbacks(InlineKeyboardMarkup markup) {
        return markup.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
    }

    @Test
    void should_buildStartAndCancelButtons_when_disclaimerKeyboardCalled() {
        InlineKeyboardMarkup markup = factory.disclaimerKeyboard(5L);

        assertThat(texts(markup)).containsExactly("✅ Начать", "❌ Отмена");
        assertThat(callbacks(markup)).containsExactly(
                "PLANT:DIAG:BEGIN:5", "PLANT:DIAG:CANCEL:5");
    }

    @Test
    void should_buildOneRowPerSymptomPlusCancelRow_when_symptomKeyboardCalled() {
        InlineKeyboardMarkup markup = factory.symptomKeyboard(7L);

        assertThat(markup.getKeyboard()).hasSize(DiagnosisSymptom.values().length + 1);
        assertThat(callbacks(markup)).contains(
                "PLANT:DIAG:SYMPTOM:7:yellow_leaves",
                "PLANT:DIAG:SYMPTOM:7:pests_or_web",
                "PLANT:DIAG:CANCEL:7"
        );
        assertThat(texts(markup)).contains(DiagnosisSymptom.YELLOW_LEAVES.label());
    }

    @Test
    void should_buildOneRowPerWateringOptionPlusCancelRow_when_wateringKeyboardCalled() {
        InlineKeyboardMarkup markup = factory.wateringKeyboard(7L);

        assertThat(markup.getKeyboard()).hasSize(WateringFrequency.values().length + 1);
        assertThat(callbacks(markup)).contains(
                "PLANT:DIAG:WATERING:7:more",
                "PLANT:DIAG:WATERING:7:unknown",
                "PLANT:DIAG:CANCEL:7"
        );
    }

    @Test
    void should_buildOneRowPerSoilOptionPlusCancelRow_when_soilKeyboardCalled() {
        InlineKeyboardMarkup markup = factory.soilKeyboard(7L);

        assertThat(markup.getKeyboard()).hasSize(SoilState.values().length + 1);
        assertThat(callbacks(markup)).contains(
                "PLANT:DIAG:SOIL:7:wet",
                "PLANT:DIAG:SOIL:7:dry",
                "PLANT:DIAG:CANCEL:7"
        );
    }

    @Test
    void should_buildOneRowPerLightOptionPlusCancelRow_when_lightKeyboardCalled() {
        InlineKeyboardMarkup markup = factory.lightKeyboard(7L);

        assertThat(markup.getKeyboard()).hasSize(LightCondition.values().length + 1);
        assertThat(callbacks(markup)).contains(
                "PLANT:DIAG:LIGHT:7:direct_sun",
                "PLANT:DIAG:LIGHT:7:low_light",
                "PLANT:DIAG:CANCEL:7"
        );
    }

    @Test
    void should_buildOneRowPerRecentChangesOptionPlusCancelRow_when_recentChangesKeyboardCalled() {
        InlineKeyboardMarkup markup = factory.recentChangesKeyboard(7L);

        assertThat(markup.getKeyboard()).hasSize(RecentChanges.values().length + 1);
        assertThat(callbacks(markup)).contains(
                "PLANT:DIAG:CHANGES:7:moved_or_repotted",
                "PLANT:DIAG:CHANGES:7:new_plant",
                "PLANT:DIAG:CANCEL:7"
        );
    }

    @Test
    void should_buildOneRowPerPestOptionPlusCancelRow_when_pestsKeyboardCalled() {
        InlineKeyboardMarkup markup = factory.pestsKeyboard(7L);

        assertThat(markup.getKeyboard()).hasSize(PestPresence.values().length + 1);
        assertThat(callbacks(markup)).contains(
                "PLANT:DIAG:PESTS:7:yes",
                "PLANT:DIAG:PESTS:7:unknown",
                "PLANT:DIAG:CANCEL:7"
        );
    }

    @Test
    void should_buildNoteRowAndPhotoDoneRow_when_resultKeyboardCalled() {
        InlineKeyboardMarkup markup = factory.resultKeyboard(3L);

        assertThat(markup.getKeyboard()).hasSize(2);
        assertThat(markup.getKeyboard().get(0)).hasSize(1);
        assertThat(markup.getKeyboard().get(1)).hasSize(2);
        assertThat(texts(markup)).containsExactly(
                "📝 Записать в заметки", "📸 Сделать фото", "✅ Готово");
        assertThat(callbacks(markup)).containsExactly(
                "PLANT:DIAG:SAVE_NOTE:3", "PLANT:DIAG:PHOTO:3", "PLANT:DIAG:DONE:3");
    }

    @Test
    void should_buildNoteRowAndDoneRowWithoutPhotoButton_when_afterPhotoKeyboardCalled() {
        InlineKeyboardMarkup markup = factory.afterPhotoKeyboard(3L);

        assertThat(markup.getKeyboard()).hasSize(2);
        assertThat(texts(markup)).containsExactly("📝 Записать в заметки", "✅ Готово");
        assertThat(callbacks(markup)).containsExactly(
                "PLANT:DIAG:SAVE_NOTE:3", "PLANT:DIAG:DONE:3");
    }
}

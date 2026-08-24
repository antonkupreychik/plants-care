package com.plantcare.core.diagnosis;

import com.plantcare.core.domain.Plant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Дополнительные ветки {@link DiagnosisTextFormatter}, не покрытые
 * {@link DiagnosisTextFormatterTest}: отсутствующий симптом и пустые секции
 * результата/заметки (appendSection / appendPlainSection короткое замыкание
 * на null/пустом списке).
 */
class DiagnosisTextFormatterCoverageTest {

    private DiagnosisTextFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new DiagnosisTextFormatter();
    }

    @Test
    void should_omitSymptomLine_when_resultBuiltWithNullSymptom() {
        Plant plant = Plant.builder().name("Замиокулькас").build();
        DiagnosisAnswers answers = DiagnosisAnswers.empty(1L);
        DiagnosisResult result = new DiagnosisResult(
                List.of("Причина"), List.of("Действие"), List.of("Проверка"));

        String text = formatter.result(plant, answers, result);

        assertThat(text).doesNotContain("Симптом:");
        assertThat(text).contains("Результат диагностики");
    }

    @Test
    void should_omitAllSections_when_resultBuiltWithEmptyLists() {
        Plant plant = Plant.builder().name("Замиокулькас").build();
        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.YELLOW_LEAVES,
                WateringFrequency.AS_USUAL,
                SoilState.NORMAL,
                LightCondition.BRIGHT_DIFFUSED,
                RecentChanges.NO_CHANGES,
                PestPresence.NO
        );
        DiagnosisResult result = new DiagnosisResult(List.of(), List.of(), List.of());

        String text = formatter.result(plant, answers, result);

        assertThat(text).doesNotContain("Возможные причины");
        assertThat(text).doesNotContain("Что сделать сейчас");
        assertThat(text).doesNotContain("Проверь дополнительно");
        assertThat(text).contains("Симптом: " + DiagnosisSymptom.YELLOW_LEAVES.label());
    }

    @Test
    void should_omitSymptomLine_when_noteTextBuiltWithNullSymptom() {
        Plant plant = Plant.builder().name("Замиокулькас").build();
        DiagnosisAnswers answers = DiagnosisAnswers.empty(1L);
        DiagnosisResult result = new DiagnosisResult(
                List.of("Причина"), List.of("Действие"), List.of("Проверка"));

        String text = formatter.noteText(plant, answers, result);

        assertThat(text).doesNotContain("Симптом:");
        assertThat(text).contains("Диагностика от");
    }

    @Test
    void should_omitAllSections_when_noteTextBuiltWithEmptyLists() {
        Plant plant = Plant.builder().name("Замиокулькас").build();
        DiagnosisAnswers answers = DiagnosisAnswers.empty(1L);
        DiagnosisResult result = new DiagnosisResult(List.of(), List.of(), List.of());

        String text = formatter.noteText(plant, answers, result);

        assertThat(text).doesNotContain("Возможные причины");
        assertThat(text).doesNotContain("Что сделать сейчас");
        assertThat(text).doesNotContain("Проверь дополнительно");
    }

    @Test
    void should_omitAllSections_when_resultBuiltWithNullLists() {
        Plant plant = Plant.builder().name("Замиокулькас").build();
        DiagnosisAnswers answers = DiagnosisAnswers.empty(1L);
        DiagnosisResult result = new DiagnosisResult(null, null, null);

        String text = formatter.result(plant, answers, result);

        assertThat(text).doesNotContain("Возможные причины");
        assertThat(text).doesNotContain("Что сделать сейчас");
        assertThat(text).doesNotContain("Проверь дополнительно");
        assertThat(text).contains("Результат диагностики");
    }

    @Test
    void should_omitAllSections_when_noteTextBuiltWithNullLists() {
        Plant plant = Plant.builder().name("Замиокулькас").build();
        DiagnosisAnswers answers = DiagnosisAnswers.empty(1L);
        DiagnosisResult result = new DiagnosisResult(null, null, null);

        String text = formatter.noteText(plant, answers, result);

        assertThat(text).doesNotContain("Возможные причины");
        assertThat(text).doesNotContain("Что сделать сейчас");
        assertThat(text).doesNotContain("Проверь дополнительно");
        assertThat(text).contains("Диагностика от");
    }

    @Test
    void should_renderEmptyPlantName_when_plantNameIsNull() {
        Plant plant = Plant.builder().build();

        String text = formatter.disclaimer(plant);

        assertThat(text).contains("Растение: **");
    }

    @Test
    void should_buildWizardQuestionTexts_when_eachQuestionMethodCalled() {
        assertThat(formatter.wateringQuestion())
                .contains("поливом");
        assertThat(formatter.soilQuestion())
                .contains("грунт");
        assertThat(formatter.lightQuestion())
                .contains("свет");
        assertThat(formatter.recentChangesQuestion())
                .contains("изменения");
        assertThat(formatter.pestsQuestion())
                .contains("насекомых");
    }
}

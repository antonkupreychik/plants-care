package com.plantcare.core.diagnosis;

import com.plantcare.core.domain.Plant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisTextFormatterTest {

    private DiagnosisTextFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new DiagnosisTextFormatter();
    }

    @Test
    void shouldBuildDisclaimerWithPlantName() {
        Plant plant = Plant.builder()
                .name("Монстера")
                .build();

        String text = formatter.disclaimer(plant);

        assertThat(text).contains("Диагностика растения");
        assertThat(text).contains("Монстера");
        assertThat(text).contains("не ставлю точный диагноз");
    }

    @Test
    void shouldBuildSymptomQuestionWithPlantName() {
        Plant plant = Plant.builder()
                .name("Фикус")
                .build();

        String text = formatter.symptomQuestion(plant);

        assertThat(text).contains("Фикус");
        assertThat(text).contains("Что происходит");
    }

    @Test
    void shouldBuildResultWithAllSections() {
        Plant plant = Plant.builder()
                .name("Монстера")
                .build();

        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.YELLOW_LEAVES,
                WateringFrequency.MORE_THAN_USUAL,
                SoilState.WET,
                LightCondition.BRIGHT_DIFFUSED,
                RecentChanges.NO_CHANGES,
                PestPresence.NO
        );

        DiagnosisResult result = new DiagnosisResult(
                List.of("Возможен перелив."),
                List.of("Поставь полив на паузу."),
                List.of("Проверь дренаж.")
        );

        String text = formatter.result(plant, answers, result);

        assertThat(text).contains("Результат диагностики");
        assertThat(text).contains("Монстера");
        assertThat(text).contains("Возможные причины");
        assertThat(text).contains("Что сделать сейчас");
        assertThat(text).contains("Проверь дополнительно");
        assertThat(text).contains("Листья желтеют");
    }

    @Test
    void shouldBuildNoteText() {
        Plant plant = Plant.builder()
                .name("Монстера")
                .build();

        DiagnosisAnswers answers = new DiagnosisAnswers(
                1L,
                DiagnosisSymptom.BROWN_DRY_TIPS,
                WateringFrequency.LESS_THAN_USUAL,
                SoilState.DRY,
                LightCondition.DIRECT_SUN,
                RecentChanges.NO_CHANGES,
                PestPresence.NO
        );

        DiagnosisResult result = new DiagnosisResult(
                List.of("Возможна пересушка."),
                List.of("Полей умеренно."),
                List.of("Проверь влажность воздуха.")
        );

        String text = formatter.noteText(plant, answers, result);

        assertThat(text).contains("Диагностика от");
        assertThat(text).contains("Монстера");
        assertThat(text).contains("Коричневые сухие кончики");
        assertThat(text).contains("Возможные причины");
        assertThat(text).contains("Что сделать сейчас");
    }

    @Test
    void shouldEscapeMarkdownInPlantName() {
        Plant plant = Plant.builder()
                .name("Монстера *test*")
                .build();

        String text = formatter.disclaimer(plant);

        assertThat(text).contains("Монстера \\*test\\*");
    }

    @Test
    void shouldBuildPhotoTexts() {
        assertThat(formatter.photoPrompt())
                .contains("Отправь фото")
                .contains("не анализирую");

        assertThat(formatter.photoReceived())
                .contains("Фото получил")
                .contains("не анализирую");
    }
}
package com.plantcare.core.diagnosis;

import com.plantcare.core.service.DiseaseCard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит-тесты форматирования подсказки болезней в диагностике (issue #140).
 *
 * diseaseHint — чистая функция форматирования, Spring/Telegram не нужны.
 */
class DiagnosisTextFormatterDiseaseHintTest {

    private final DiagnosisTextFormatter formatter = new DiagnosisTextFormatter();

    private DiseaseCard card(Long id, String name) {
        return new DiseaseCard(id, name, null, "симптомы", "лечение", "профилактика");
    }

    @Test
    void should_return_null_when_matches_is_null() {
        String hint = formatter.diseaseHint(null);

        assertThat(hint).isNull();
    }

    @Test
    void should_return_null_when_matches_is_empty() {
        String hint = formatter.diseaseHint(List.of());

        assertThat(hint).isNull();
    }

    @Test
    void should_build_hint_with_disease_links_when_matches_present() {
        List<DiseaseCard> matches = List.of(
                card(5L, "Паутинный клещ"),
                card(12L, "Щитовка")
        );

        String hint = formatter.diseaseHint(matches);

        assertThat(hint)
                .isNotBlank()
                .contains("Паутинный клещ")
                .contains("Щитовка")
                .contains("/disease_5")
                .contains("/disease_12")
                .contains("не диагноз");
    }

    @Test
    void should_render_one_link_per_match_when_multiple_matches() {
        List<DiseaseCard> matches = List.of(
                card(1L, "Тля"),
                card(2L, "Трипсы"),
                card(3L, "Белокрылка")
        );

        String hint = formatter.diseaseHint(matches);

        long linkCount = hint.lines()
                .filter(line -> line.contains("/disease_"))
                .count();
        assertThat(linkCount).isEqualTo(3);
    }
}

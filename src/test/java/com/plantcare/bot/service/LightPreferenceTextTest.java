package com.plantcare.bot.service;

import com.plantcare.core.domain.enums.LightPreference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит-тесты презентационного хелпера {@link LightPreferenceText} (issue #135).
 * Чистая presentation-логика без Spring/БД — обычный unit.
 */
class LightPreferenceTextTest {

    @ParameterizedTest
    @CsvSource({
            "SHADE,тень",
            "PARTIAL,'полутень, рассеянный свет'",
            "BRIGHT,яркий рассеянный свет",
            "DIRECT,прямые солнечные лучи"
    })
    void should_return_expected_phrase_when_light_preference_given(
            LightPreference preference, String expected) {
        // act
        String phrase = LightPreferenceText.phrase(preference);

        // assert
        assertThat(phrase).isEqualTo(expected);
    }

    @Test
    void should_return_empty_string_when_preference_is_null() {
        // act
        String phrase = LightPreferenceText.phrase(null);

        // assert
        assertThat(phrase).isEmpty();
    }

    @Test
    void should_return_non_blank_phrase_for_every_enum_value() {
        // arrange / act / assert — гарантия, что новый enum-кейс не останется
        // без текста (switch исчерпывающий, но фраза не должна быть пустой/null)
        for (LightPreference preference : LightPreference.values()) {
            assertThat(LightPreferenceText.phrase(preference))
                    .as("phrase for %s", preference)
                    .isNotBlank();
        }
    }
}

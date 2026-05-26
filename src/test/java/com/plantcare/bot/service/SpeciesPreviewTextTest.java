package com.plantcare.bot.service;

import com.plantcare.core.domain.enums.LightPreference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит-тесты превью-сообщения вида {@link SpeciesPreviewText} (issue #135 / #9).
 * Чистая presentation-логика — обычный unit без Spring.
 */
class SpeciesPreviewTextTest {

    private static final String LIGHT_PREFIX = "💡 Для этого вида лучше: ";

    @Test
    void should_include_light_recommendation_when_light_preference_given() {
        // arrange
        var light = LightPreference.BRIGHT;

        // act
        String preview = SpeciesPreviewText.build("Монстера", 7, light);

        // assert
        assertThat(preview)
                .contains(LIGHT_PREFIX)
                .contains(LightPreferenceText.phrase(light))
                .contains("✨ *Монстера*")
                .contains("Полив: раз в 7 дней")
                .contains("Тебе подходит?");
    }

    @Test
    void should_omit_light_recommendation_but_stay_valid_when_light_preference_is_null() {
        // arrange / act — null освещение не должно ломать превью
        String preview = SpeciesPreviewText.build("Фикус", 5, null);

        // assert
        assertThat(preview).doesNotContain("💡");
        assertThat(preview)
                .contains("✨ *Фикус*")
                .contains("Полив: раз в 5 дней")
                .contains("Тебе подходит?");
    }

    @Test
    void should_render_watering_by_need_when_watering_days_is_null() {
        // act
        String preview = SpeciesPreviewText.build("Кактус", null, LightPreference.DIRECT);

        // assert
        assertThat(preview)
                .contains("Полив: по необходимости")
                .doesNotContain("раз в")
                .contains(LIGHT_PREFIX + LightPreferenceText.phrase(LightPreference.DIRECT))
                .contains("Тебе подходит?");
    }

    @Test
    void should_render_watering_by_need_and_no_light_when_both_null() {
        // arrange / act — оба опциональных поля null: минимальное валидное превью
        String preview = SpeciesPreviewText.build("Неизвестный вид", null, null);

        // assert
        assertThat(preview)
                .contains("✨ *Неизвестный вид*")
                .contains("Полив: по необходимости")
                .contains("Тебе подходит?")
                .doesNotContain("💡");
    }

    @Test
    void should_use_exact_phrase_from_light_preference_text_for_each_value() {
        // arrange / act / assert — единый источник текста: фраза в превью совпадает
        // с LightPreferenceText, чтобы рекомендация не разъезжалась с карточкой
        for (LightPreference preference : LightPreference.values()) {
            String preview = SpeciesPreviewText.build("Вид", 3, preference);

            assertThat(preview)
                    .as("preview for %s", preference)
                    .contains(LIGHT_PREFIX + LightPreferenceText.phrase(preference));
        }
    }
}

package com.plantcare.bot.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Unit-тесты для LocationEmojiKeyboard")
class LocationEmojiKeyboardTest {

    @Test
    @DisplayName("Создаёт клавиатуру с 4 рядами")
    void shouldCreateKeyboardWithFourRows() {
        InlineKeyboardMarkup keyboard = LocationEmojiKeyboard.build("LOCATION_EMOJI:");

        assertThat(keyboard).isNotNull();
        assertThat(keyboard.getKeyboard()).hasSize(4);
    }

    @Test
    @DisplayName("В каждом ряду по 4 кнопки")
    void shouldCreateFourButtonsInEachRow() {
        InlineKeyboardMarkup keyboard = LocationEmojiKeyboard.build("LOCATION_EMOJI:");

        assertThat(keyboard.getKeyboard())
                .allSatisfy(row -> assertThat(row).hasSize(4));
    }

    @Test
    @DisplayName("Кнопки содержат нужные emoji")
    void shouldContainExpectedEmojiButtons() {
        InlineKeyboardMarkup keyboard = LocationEmojiKeyboard.build("LOCATION_EMOJI:");

        List<String> buttonTexts = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getText)
                .toList();

        assertThat(buttonTexts).containsExactly(
                "🛋", "🛏", "🍳", "🌿",
                "💼", "🚿", "🪴", "☀️",
                "🌵", "🌸", "🌱", "🍀",
                "🏠", "🪟", "❤️", "✨"
        );
    }

    @Test
    @DisplayName("callback_data формируется из prefix + emoji")
    void shouldBuildCallbackDataFromPrefixAndEmoji() {
        InlineKeyboardMarkup keyboard = LocationEmojiKeyboard.build("LOCATION_EMOJI:");

        List<String> callbacks = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();

        assertThat(callbacks).containsExactly(
                "LOCATION_EMOJI:🛋",
                "LOCATION_EMOJI:🛏",
                "LOCATION_EMOJI:🍳",
                "LOCATION_EMOJI:🌿",

                "LOCATION_EMOJI:💼",
                "LOCATION_EMOJI:🚿",
                "LOCATION_EMOJI:🪴",
                "LOCATION_EMOJI:☀️",

                "LOCATION_EMOJI:🌵",
                "LOCATION_EMOJI:🌸",
                "LOCATION_EMOJI:🌱",
                "LOCATION_EMOJI:🍀",

                "LOCATION_EMOJI:🏠",
                "LOCATION_EMOJI:🪟",
                "LOCATION_EMOJI:❤️",
                "LOCATION_EMOJI:✨"
        );
    }

    @Test
    @DisplayName("Работает с другим callback prefix")
    void shouldUseCustomCallbackPrefix() {
        InlineKeyboardMarkup keyboard = LocationEmojiKeyboard.build("PLANT_LOCATION_EMOJI:");

        List<String> callbacks = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();

        assertThat(callbacks).allMatch(callback -> callback.startsWith("PLANT_LOCATION_EMOJI:"));
        assertThat(callbacks).contains("PLANT_LOCATION_EMOJI:🛋");
        assertThat(callbacks).contains("PLANT_LOCATION_EMOJI:✨");
    }
}
package com.plantcare.bot.util;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.List;
import java.util.Optional;

public final class LocationEmojiKeyboard {

    private LocationEmojiKeyboard() {
    }

    public record LocationPreset(String key, String name, String emoji) {
        public String displayName() {
            return emoji + " " + name;
        }
    }

    private static final List<LocationPreset> PRESETS = List.of(
            new LocationPreset("LIVING_ROOM", "Гостиная", "🛋"),
            new LocationPreset("BEDROOM", "Спальня", "🛏"),
            new LocationPreset("KITCHEN", "Кухня", "🍳"),
            new LocationPreset("BALCONY", "Балкон", "☀️"),
            new LocationPreset("OFFICE", "Кабинет", "💼"),
            new LocationPreset("BATHROOM", "Ванная", "🚿"),
            new LocationPreset("WINDOW", "Подоконник", "🪟"),
            new LocationPreset("PLANTS", "Зелёный уголок", "🪴")
    );

    public static InlineKeyboardMarkup build(String callbackPrefix) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        emojiButton("🛋", callbackPrefix),
                        emojiButton("🛏", callbackPrefix),
                        emojiButton("🍳", callbackPrefix),
                        emojiButton("🌿", callbackPrefix)
                ))
                .keyboardRow(new InlineKeyboardRow(
                        emojiButton("💼", callbackPrefix),
                        emojiButton("🚿", callbackPrefix),
                        emojiButton("🪴", callbackPrefix),
                        emojiButton("☀️", callbackPrefix)
                ))
                .keyboardRow(new InlineKeyboardRow(
                        emojiButton("🌵", callbackPrefix),
                        emojiButton("🌸", callbackPrefix),
                        emojiButton("🌱", callbackPrefix),
                        emojiButton("🍀", callbackPrefix)
                ))
                .keyboardRow(new InlineKeyboardRow(
                        emojiButton("🏠", callbackPrefix),
                        emojiButton("🪟", callbackPrefix),
                        emojiButton("❤️", callbackPrefix),
                        emojiButton("✨", callbackPrefix)
                ))
                .build();
    }

    public static InlineKeyboardMarkup buildLocationPresets(String callbackPrefix) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        presetButton(PRESETS.get(0), callbackPrefix),
                        presetButton(PRESETS.get(1), callbackPrefix)
                ))
                .keyboardRow(new InlineKeyboardRow(
                        presetButton(PRESETS.get(2), callbackPrefix),
                        presetButton(PRESETS.get(3), callbackPrefix)
                ))
                .keyboardRow(new InlineKeyboardRow(
                        presetButton(PRESETS.get(4), callbackPrefix),
                        presetButton(PRESETS.get(5), callbackPrefix)
                ))
                .keyboardRow(new InlineKeyboardRow(
                        presetButton(PRESETS.get(6), callbackPrefix),
                        presetButton(PRESETS.get(7), callbackPrefix)
                ))
                .build();
    }

    public static Optional<LocationPreset> findPresetByKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }

        return PRESETS.stream()
                .filter(preset -> preset.key().equals(key))
                .findFirst();
    }

    private static InlineKeyboardButton emojiButton(String emoji, String callbackPrefix) {
        return InlineKeyboardButton.builder()
                .text(emoji)
                .callbackData(callbackPrefix + emoji)
                .build();
    }

    private static InlineKeyboardButton presetButton(LocationPreset preset, String callbackPrefix) {
        return InlineKeyboardButton.builder()
                .text(preset.displayName())
                .callbackData(callbackPrefix + preset.key())
                .build();
    }
}
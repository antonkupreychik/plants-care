package com.plantcare.bot.util;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

public final class LocationEmojiKeyboard {

    private LocationEmojiKeyboard() {
    }

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

    private static InlineKeyboardButton emojiButton(String emoji, String callbackPrefix) {
        return InlineKeyboardButton.builder()
                .text(emoji)
                .callbackData(callbackPrefix + emoji)
                .build();
    }
}
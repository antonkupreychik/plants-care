package com.plantcare.bot.util;

public final class EmojiValidator {

    private EmojiValidator() {
    }

    public static boolean isValidEmoji(String value) {
        if (value == null) {
            return true;
        }

        String emoji = value.trim();

        if (emoji.isEmpty()) {
            return true;
        }

        if (emoji.length() > 16) {
            return true;
        }

        int codePointCount = emoji.codePointCount(0, emoji.length());

        if (codePointCount > 4) {
            return true;
        }

        return emoji.codePoints().noneMatch(EmojiValidator::isEmojiCodePoint);
    }

    private static boolean isEmojiCodePoint(int codePoint) {
        return
                codePoint >= 0x1F300 && codePoint <= 0x1FAFF || // symbols, pictographs, transport, plants, etc.
                codePoint >= 0x2600 && codePoint <= 0x27BF ||   // misc symbols, dingbats
                codePoint >= 0x2300 && codePoint <= 0x23FF;     // extra symbols like ⏰
    }
}
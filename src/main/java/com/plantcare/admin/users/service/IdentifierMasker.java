package com.plantcare.admin.users.service;

/**
 * Маскирование чувствительных идентификаторов для админского UI (issue #93).
 *
 * <p>Задача — дать саппорту достаточно, чтобы сверить идентификатор с тем, что
 * называет пользователь («да, это ваш gmail», «да, последние цифры 4567»), но не
 * выкладывать на экран (и в скриншот тикета) целиком email или Apple/Google
 * {@code sub}. Односторонняя функция: восстановить оригинал из результата нельзя.
 *
 * <p>Эти же значения <b>нельзя логировать</b> — ни в открытом виде, ни
 * замаскированными: в логах у нас есть только {@code user_id} и имя провайдера.
 */
public final class IdentifierMasker {

    /** Сколько символов оставляем видимыми с каждого края у opaque-идентификатора. */
    static final int SUBJECT_EDGE = 4;

    /**
     * Плейсхолдер скрытой части. Намеренно ASCII: значение попадает в HTML через
     * {@code th:text}, и звёздочки не зависят от того, как Thymeleaf решит
     * экранировать не-ASCII символ.
     */
    static final String HIDDEN = "***";

    private IdentifierMasker() {
    }

    /**
     * {@code alexander@example.com} → {@code al***@example.com}. Домен остаётся:
     * он и так виден в тикете саппорта и сам по себе никого не идентифицирует.
     * Локальная часть короче трёх символов скрывается целиком.
     */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String value = email.trim();
        int at = value.lastIndexOf('@');
        if (at <= 0 || at == value.length() - 1) {
            // Не похоже на email — не гадаем, скрываем целиком.
            return HIDDEN;
        }
        String local = value.substring(0, at);
        String domain = value.substring(at);
        if (local.length() < 3) {
            return HIDDEN + domain;
        }
        return local.substring(0, 2) + HIDDEN + domain;
    }

    /**
     * Opaque-идентификатор провайдера (Apple/Google {@code sub}):
     * {@code 000123.abcdef...9012} → {@code 0001***9012}. Значения короче
     * {@code 2 * SUBJECT_EDGE + 1} скрываются целиком — иначе «маска» вернула бы
     * почти весь оригинал.
     */
    public static String maskSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return null;
        }
        String value = subject.trim();
        if (value.length() <= SUBJECT_EDGE * 2) {
            return HIDDEN;
        }
        return value.substring(0, SUBJECT_EDGE) + HIDDEN
                + value.substring(value.length() - SUBJECT_EDGE);
    }

    /**
     * Telegram chat id → {@code ***4567}. Короткие id (тестовые/служебные)
     * скрываются целиком.
     */
    public static String maskChatId(Long chatId) {
        if (chatId == null) {
            return null;
        }
        // Не Math.abs: для Long.MIN_VALUE он возвращает отрицательное значение.
        String value = Long.toString(chatId).replace("-", "");
        if (value.length() <= SUBJECT_EDGE) {
            return HIDDEN;
        }
        return HIDDEN + value.substring(value.length() - SUBJECT_EDGE);
    }
}

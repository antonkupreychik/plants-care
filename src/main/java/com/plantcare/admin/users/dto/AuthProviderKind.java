package com.plantcare.admin.users.dto;

/**
 * Провайдер аутентификации, отображаемый в секции «Аутентификация» на странице
 * юзера в админке (issue #93).
 *
 * <p>Соответствует колонкам-идентификаторам в таблице {@code users} (миграция
 * V31): {@code telegram_chat_id}, {@code email}, {@code apple_subject},
 * {@code google_subject}. Отдельной таблицы привязок пока нет — она появится
 * вместе с механизмом слияния аккаунтов (issue #89).
 *
 * <p>Не путать с {@code com.plantcare.api.auth.service.AuthService.Provider}:
 * тот описывает провайдера ВХОДА (Telegram-входа в API нет), этот — привязку,
 * которую видит и может разорвать админ.
 */
public enum AuthProviderKind {

    TELEGRAM("Telegram", "telegram_chat_id"),
    EMAIL("Email", "email"),
    APPLE("Apple", "apple_subject"),
    GOOGLE("Google", "google_subject");

    private final String label;
    private final String column;

    AuthProviderKind(String label, String column) {
        this.label = label;
        this.column = column;
    }

    public String getLabel() {
        return label;
    }

    /** Имя колонки в {@code users} — только для отображения в UI, не для SQL. */
    public String getColumn() {
        return column;
    }
}

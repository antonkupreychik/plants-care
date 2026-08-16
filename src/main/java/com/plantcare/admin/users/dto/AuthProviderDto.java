package com.plantcare.admin.users.dto;

import java.time.Instant;

/**
 * Строка таблицы «Аутентификация» на странице юзера (issue #93).
 *
 * @param kind             провайдер
 * @param linked           привязан ли провайдер (идентификатор не NULL)
 * @param maskedIdentifier замаскированный идентификатор ({@code null}, если не привязан)
 * @param verified         только для {@link AuthProviderKind#EMAIL}: подтверждён ли email
 * @param linkedAt         дата привязки. Сейчас всегда {@code null}: per-provider
 *                         таймстемпы в схеме не хранятся, они появятся вместе с
 *                         таблицей привязок из issue #89. Колонка в UI оставлена,
 *                         чтобы форма таблицы не менялась, когда данные появятся.
 * @param lastUsedAt       дата последнего использования. Заполняется только для
 *                         EMAIL — берётся из {@code magic_link_tokens.consumed_at}.
 *                         Для остальных провайдеров логов входа нет (issue #89).
 */
public record AuthProviderDto(
        AuthProviderKind kind,
        boolean linked,
        String maskedIdentifier,
        boolean verified,
        Instant linkedAt,
        Instant lastUsedAt
) {

    public static AuthProviderDto absent(AuthProviderKind kind) {
        return new AuthProviderDto(kind, false, null, false, null, null);
    }

    /** Удобно для Thymeleaf: {@code p.label} вместо {@code p.kind.label}. */
    public String getLabel() {
        return kind.getLabel();
    }
}

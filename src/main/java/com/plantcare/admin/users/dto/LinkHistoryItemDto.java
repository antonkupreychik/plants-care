package com.plantcare.admin.users.dto;

import java.time.Instant;

/**
 * Строка раздела «История привязок» (issue #93).
 *
 * <p>Источник — таблица {@code magic_link_tokens}: это единственный лог привязок,
 * который существует сегодня. Колонки {@code source_provider} /
 * {@code target_provider} / {@code ip} из issue заполнить нечем — они появятся
 * вместе с QR-привязкой аккаунтов (issue #89).
 *
 * @param at         момент выпуска токена ({@code created_at})
 * @param action     {@code GENERATED} | {@code CLAIMED} | {@code EXPIRED}
 * @param expiresAt  срок жизни токена
 * @param consumedAt когда токен был использован ({@code null} — не использован)
 */
public record LinkHistoryItemDto(
        Instant at,
        String action,
        Instant expiresAt,
        Instant consumedAt
) {

    public static final String ACTION_GENERATED = "GENERATED";
    public static final String ACTION_CLAIMED = "CLAIMED";
    public static final String ACTION_EXPIRED = "EXPIRED";

    /**
     * Токен «живой» (выпущен и ещё не использован/не протух) → GENERATED;
     * использован → CLAIMED; не использован и срок вышел → EXPIRED.
     */
    public static LinkHistoryItemDto of(Instant createdAt, Instant expiresAt,
                                        Instant consumedAt, Instant now) {
        String action;
        if (consumedAt != null) {
            action = ACTION_CLAIMED;
        } else if (expiresAt != null && expiresAt.isBefore(now)) {
            action = ACTION_EXPIRED;
        } else {
            action = ACTION_GENERATED;
        }
        return new LinkHistoryItemDto(createdAt, action, expiresAt, consumedAt);
    }
}

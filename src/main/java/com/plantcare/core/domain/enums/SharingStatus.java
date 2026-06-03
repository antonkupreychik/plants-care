package com.plantcare.core.domain.enums;

/**
 * Статус приглашения соухаживающего (issue #191).
 *
 * <ul>
 *   <li>{@link #PENDING} — приглашение выпущено владельцем, ещё не принято.</li>
 *   <li>{@link #ACCEPTED} — приглашение принято приглашённым.</li>
 * </ul>
 */
public enum SharingStatus {
    PENDING,
    ACCEPTED
}

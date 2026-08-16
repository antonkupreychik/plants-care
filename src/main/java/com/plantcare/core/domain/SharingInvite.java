package com.plantcare.core.domain;

import com.plantcare.core.domain.base.BaseEntity;
import com.plantcare.core.domain.enums.SharingStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Приглашение соухаживающего (issue #191, экран 26 «Совместный уход»).
 *
 * <p>Владелец ({@code inviter}) выпускает приглашение на набор растений
 * ({@code plantIds}) для контакта {@code inviteeContact} (@username / телефон).
 * Контакт хранится свободной строкой, потому что у приглашённого может ещё не
 * быть аккаунта на момент инвайта. Статус стартует с {@link SharingStatus#PENDING}.
 *
 * <p>id и created_at наследуются из {@link BaseEntity}.
 */
@Entity
@Table(name = "sharing_invites")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SharingInvite extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inviter_user_id", nullable = false)
    private User inviter;

    @Column(name = "invitee_contact", nullable = false, length = 255)
    private String inviteeContact;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SharingStatus status = SharingStatus.PENDING;

    @Column(name = "can_log_care", nullable = false)
    private boolean canLogCare = false;

    /**
     * Идентификаторы растений, на которые распространяется приглашение.
     * Принадлежность растений владельцу проверяется на сервисном слое до записи.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "sharing_invite_plants",
            joinColumns = @JoinColumn(name = "invite_id")
    )
    @Column(name = "plant_id", nullable = false)
    private Set<Long> plantIds = new LinkedHashSet<>();

    public SharingInvite(User inviter, String inviteeContact, boolean canLogCare, Set<Long> plantIds) {
        this.inviter = inviter;
        this.inviteeContact = inviteeContact;
        this.canLogCare = canLogCare;
        this.status = SharingStatus.PENDING;
        this.plantIds = new LinkedHashSet<>(plantIds);
    }
}

package com.plantcare.core.domain;

import com.plantcare.core.domain.base.BaseEntity;
import com.plantcare.core.domain.enums.SuggestionStatus;
import jakarta.persistence.Column;
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

import java.time.LocalDateTime;

/**
 * Трекер идемпотентности подсказок расходников перед пересадкой (issue #141).
 *
 * <p>По строке на {@code (user_id, plant_id, source_event_id)}: шедулер раз в
 * день вычисляет предстоящую пересадку как {@code last TRANSPLANT + интервал}
 * из конфига и, если по этому событию ещё не подсказывал, создаёт строку
 * {@link SuggestionStatus#SUGGESTED} и шлёт мягкую нотификацию с кнопками
 * «➕ В список покупок» / «Не нужно».
 *
 * <p>{@code source_event_id} — id записи TRANSPLANT в {@link PlantEvent}, от
 * которой посчитана пересадка. Это ключ идемпотентности: новая пересадка →
 * новый source_event_id → можно подсказать снова. UNIQUE на
 * {@code (user_id, plant_id, source_event_id)} (см. V29) фиксирует гарантию на
 * уровне БД.
 *
 * <p>id и created_at наследуются из {@link BaseEntity}.
 */
@Entity
@Table(name = "transplant_supply_suggestions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransplantSupplySuggestion extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plant_id", nullable = false)
    private Plant plant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_event_id", nullable = false)
    private PlantEvent sourceEvent;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SuggestionStatus status;

    @Column(name = "predicted_transplant_at", nullable = false)
    private LocalDateTime predictedTransplantAt;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    public TransplantSupplySuggestion(
            User user,
            Plant plant,
            PlantEvent sourceEvent,
            LocalDateTime predictedTransplantAt
    ) {
        this.user = user;
        this.plant = plant;
        this.sourceEvent = sourceEvent;
        this.predictedTransplantAt = predictedTransplantAt;
        this.status = SuggestionStatus.SUGGESTED;
    }

    /**
     * Идемпотентно переводит подсказку в {@link SuggestionStatus#ADDED} и
     * фиксирует момент реакции. Вызывается, когда юзер добавил расходники в
     * список покупок.
     */
    public void markAdded(LocalDateTime respondedAt) {
        this.status = SuggestionStatus.ADDED;
        this.respondedAt = respondedAt;
    }

    /**
     * Идемпотентно переводит подсказку в {@link SuggestionStatus#DISMISSED} и
     * фиксирует момент реакции. Вызывается, когда юзер нажал «Не нужно».
     */
    public void markDismissed(LocalDateTime respondedAt) {
        this.status = SuggestionStatus.DISMISSED;
        this.respondedAt = respondedAt;
    }
}

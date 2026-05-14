package com.plantcare.bot.domain;

import com.plantcare.bot.domain.base.BaseEntity;
import com.plantcare.bot.domain.enums.PlantEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Событие из журнала растения (issue #76): пересадка, обрезка, замена грунта,
 * обработка от вредителей и т.п.
 *
 * <p>Запись иммутабельна — после создания не редактируется. Если ввёл по
 * ошибке — в v1 удаления нет (см. обсуждение в issue); по необходимости
 * добавим soft-delete в следующей итерации.
 *
 * <p>Связь с {@link Plant} — {@code ManyToOne LAZY}. На удалении растения
 * каскадно удаляются и события (см. FK в V11).
 */
@Entity
@Table(name = "plant_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlantEvent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plant_id", nullable = false)
    private Plant plant;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private PlantEventType eventType;

    /** Когда событие произошло. По умолчанию совпадает с {@code created_at}. */
    @Column(name = "event_date", nullable = false)
    private LocalDateTime eventDate;

    /**
     * Необязательный комментарий, до 500 символов. В v1 не используется при
     * создании (одно нажатие = одно событие без коммента), но колонка зарезервирована.
     */
    @Column(length = 500)
    private String comment;
}

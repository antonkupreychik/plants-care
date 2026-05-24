package com.plantcare.bot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Запись «годовщина растения уже отправлена в этом году» (issue #117).
 * Используется для идемпотентности шедулера годовщин:
 * перед отправкой шедулер пытается INSERT в эту таблицу;
 * первичный ключ (plant_id, anniversary_year) гарантирует, что в один и тот же
 * календарный год по TZ юзера не уйдёт второй пуш.
 *
 * <p>FK на {@link Plant} c {@code ON DELETE CASCADE} (см. миграцию V21):
 * при «удалить навсегда» строка уезжает вместе с растением.
 */
@Entity
@Table(name = "plant_anniversaries_sent")
@IdClass(PlantAnniversarySentId.class)
@Getter
@Setter
@NoArgsConstructor
public class PlantAnniversarySent {

    public PlantAnniversarySent(Long plantId, Integer anniversaryYear, Instant sentAt) {
        this.plantId = plantId;
        this.anniversaryYear = anniversaryYear;
        this.sentAt = sentAt;
    }

    @Id
    @Column(name = "plant_id", nullable = false)
    private Long plantId;

    @Id
    @Column(name = "anniversary_year", nullable = false)
    private Integer anniversaryYear;

    /**
     * Растение, на которое ссылается запись. LAZY — для большинства операций
     * (вставка-проверка) сама ассоциация не нужна, нужен только plantId.
     * Маппится {@code insertable=false/updatable=false}, чтобы PK-колонка
     * писалась через поле {@link #plantId}, а не через эту связь.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plant_id", insertable = false, updatable = false)
    private Plant plant;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;
}

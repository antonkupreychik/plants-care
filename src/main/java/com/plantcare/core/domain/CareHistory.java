package com.plantcare.core.domain;

import com.plantcare.core.domain.base.BaseEntity;
import com.plantcare.core.domain.enums.TaskType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "care_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CareHistory extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plant_id", nullable = false)
    private Plant plant;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 32)
    private TaskType taskType;

    @Column(name = "done_at", nullable = false)
    private LocalDateTime doneAt;

    @Column(name = "was_on_time", nullable = false)
    private boolean onTime;

    @Column(length = 500)
    private String note;

    /**
     * Обильность полива (issue #71). NULL для bulk-полива, для MISTING/FERTILIZING
     * и для исторических записей до миграции V10.
     */
    @Column(name = "was_abundant")
    private Boolean wasAbundant;

    /**
     * Земля была сухая до полива (issue #71). NULL для bulk, не-WATERING типов,
     * "не знаю" ответа и старых записей.
     */
    @Column(name = "soil_was_dry")
    private Boolean soilWasDry;

    /**
     * Идентификатор идемпотентности от мобильного клиента (issue #86).
     * NULL для записей из Telegram-бота и исторических записей.
     * Обеспечивает безопасность при retry после офлайн-синка.
     */
    @Column(name = "client_id", length = 64)
    private String clientId;

    /**
     * Ссылка на компенсирующую запись. История иммутабельна — ошибочная запись
     * не удаляется, а перекрывается компенсирующей. Резерв под Этап 3 (кнопка отмены).
     * NULL означает, что запись активна и не отменена.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private CareHistory cancelledBy;

    /**
     * Когда запись была изменена. Обновляется Hibernate при каждом UPDATE (issue #91).
     * Используется в офлайн-синке: клиент передаёт since, сервер возвращает всё,
     * что изменилось позднее.
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Возвращает true, если запись была отменена компенсирующей.
     */
    public boolean isCancelled() {
        return cancelledBy != null;
    }
}
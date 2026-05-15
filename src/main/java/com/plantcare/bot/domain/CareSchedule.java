package com.plantcare.bot.domain;

import com.plantcare.bot.domain.base.BaseEntity;
import com.plantcare.bot.domain.enums.TaskType;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(
    name = "care_schedules",
    uniqueConstraints = @UniqueConstraint(name = "uq_schedules_plant_type", columnNames = {"plant_id", "task_type"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CareSchedule extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plant_id", nullable = false)
    private Plant plant;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 32)
    private TaskType taskType;

    @Column(name = "interval_days", nullable = false)
    private Integer intervalDays;

    @Column(name = "next_due_at", nullable = false)
    private LocalDateTime nextDueAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Сдвигает следующее время задачи на интервал от заданной даты.
     * Используется при отметке "сделано": от actualDoneAt + intervalDays.
     */
    public void rescheduleFrom(LocalDateTime from) {
        rescheduleFrom(from, this.intervalDays);
    }

    /**
     * Та же логика, но с явным интервалом — для случаев, когда вызывающий
     * хочет применить сезонную корректировку или другой расчёт (issue #67).
     * Базовый {@code intervalDays} на entity не трогаем — он представляет
     * настройку юзера, а не «фактический» сезонный интервал.
     */
    public void rescheduleFrom(LocalDateTime from, int intervalDaysOverride) {
        this.nextDueAt = from.plusDays(intervalDaysOverride);
    }
}

package com.plantcare.core.domain;

import com.plantcare.core.domain.base.BaseEntity;
import com.plantcare.core.domain.enums.TaskType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Пользовательский шаблон растения (issue #68).
 *
 * <p>Хранит интервалы ухода (полив/опрыскивание/удобрение), которые можно
 * сохранить из настроенного растения и потом быстро использовать для создания
 * новых. Удаление шаблона НЕ затрагивает уже созданные из него растения —
 * связи на уровне FK с plants нет (требование issue #68).
 */
@Entity
@Table(name = "plant_templates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlantTemplate extends BaseEntity {

    /** ID пользователя-владельца. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Название шаблона, 1-40 символов, case-insensitive unique per user. */
    @Column(nullable = false, length = 40)
    private String name;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Правила ухода. Cascade ALL + orphanRemoval = удаление шаблона
     * каскадируется только на свои правила.
     */
    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PlantTemplateCareRule> careRules = new ArrayList<>();

    /**
     * Короткая подпись для списка шаблонов в UI.
     * Пример: «полив каждые 7 дн.»
     */
    public String shortDescription() {
        return careRules.stream()
                .filter(r -> r.getCareType() == TaskType.WATERING)
                .findFirst()
                .map(r -> "полив каждые " + r.getIntervalDays() + " дн.")
                .orElse("без правил полива");
    }
}

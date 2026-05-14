package com.plantcare.bot.domain;

import com.plantcare.bot.domain.base.BaseEntity;
import com.plantcare.bot.domain.enums.TaskType;
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

@Entity
@Table(
    name = "plant_template_care_rules",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_template_care_type", columnNames = {"template_id", "care_type"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlantTemplateCareRule extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    private PlantTemplate template;

    @Enumerated(EnumType.STRING)
    @Column(name = "care_type", nullable = false, length = 32)
    private TaskType careType;

    @Column(name = "interval_days", nullable = false)
    private Integer intervalDays;
}

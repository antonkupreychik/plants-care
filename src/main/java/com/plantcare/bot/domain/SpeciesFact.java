package com.plantcare.bot.domain;

import com.plantcare.bot.domain.base.BaseEntity;
import com.plantcare.bot.domain.enums.FactCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Энциклопедический факт о виде растения (ADR-011, issue #129).
 *
 * <p>Таблица {@code species_facts} создана в миграции V22. Факты группируются
 * по {@link FactCategory} и сортируются внутри категории по {@code displayOrder}.
 *
 * <p>{@code updatedAt} обновляется триггером {@code trg_species_facts_updated_at}
 * на стороне БД — поле помечено {@code insertable=false, updatable=false}, чтобы
 * Hibernate не перетирал значение, выставленное БД. {@code createdAt} наследуется
 * из {@link BaseEntity} (Hibernate {@code @CreationTimestamp} + DB default).
 */
@Entity
@Table(name = "species_facts")
@Getter
@Setter
@NoArgsConstructor
public class SpeciesFact extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "species_id", nullable = false)
    private Species species;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 24)
    private FactCategory category;

    @Column(name = "title", length = 160)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "source", length = 300)
    private String source;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    /**
     * Управляется триггером в БД. Только чтение со стороны Hibernate.
     */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}

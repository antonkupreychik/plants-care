package com.plantcare.core.domain;

import com.plantcare.core.domain.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Типичная болезнь/вредитель комнатных растений (ADR-013, issue #140).
 *
 * <p>Справочник только для чтения: данные сидятся миграцией {@code V25__seed_diseases.sql},
 * пользователь и код их не меняют. Используется в разделе «🦠 Болезни» и как мягкая
 * подсказка в диагностике (#73) — без привязки к конкретным видам (ADR-013/ADR-011).
 */
@Entity
@Table(name = "diseases")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Disease extends BaseEntity {

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "latin_name", length = 150)
    private String latinName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String symptoms;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String treatment;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String prevention;

    /**
     * CSV кодов {@link com.plantcare.core.diagnosis.DiagnosisSymptom} для матчинга
     * в диагностике (#73). Может быть {@code null}.
     */
    @Column(name = "symptom_codes", columnDefinition = "TEXT")
    private String symptomCodes;

    @Column(name = "search_tags", columnDefinition = "TEXT")
    private String searchTags;
}

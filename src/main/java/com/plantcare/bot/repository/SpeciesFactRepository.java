package com.plantcare.bot.repository;

import com.plantcare.bot.domain.SpeciesFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Доступ к энциклопедическим фактам видов (ADR-011, issue #129).
 */
@Repository
public interface SpeciesFactRepository extends JpaRepository<SpeciesFact, Long> {

    /**
     * Факты вида в порядке показа: по категории, затем по display_order.
     * Опирается на индекс {@code idx_species_facts_species_category_order} (V22).
     *
     * @param speciesId идентификатор вида
     * @return упорядоченный список фактов; пустой, если фактов нет
     */
    List<SpeciesFact> findBySpeciesIdOrderByCategoryAscDisplayOrderAsc(Long speciesId);

    /**
     * Дешёвая проверка наличия фактов у вида ({@code SELECT EXISTS}).
     * Используется для видимости кнопки «О виде» на карточке растения,
     * чтобы не грузить весь список фактов ради {@code isEmpty()}.
     * Опирается на индекс {@code idx_species_facts_species_category_order} (V22).
     *
     * @param speciesId идентификатор вида
     * @return {@code true}, если у вида есть хотя бы один факт
     */
    boolean existsBySpeciesId(Long speciesId);
}

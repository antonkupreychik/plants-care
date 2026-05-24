package com.plantcare.bot.repository;

import com.plantcare.bot.domain.PlantAnniversarySent;
import com.plantcare.bot.domain.PlantAnniversarySentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Идемпотентность годовщин растений (issue #117).
 * Маркер «(plant_id, anniversary_year) уже отправлен» — наличие записи в таблице.
 */
@Repository
public interface PlantAnniversarySentRepository
        extends JpaRepository<PlantAnniversarySent, PlantAnniversarySentId> {

    boolean existsByPlantIdAndAnniversaryYear(Long plantId, Integer anniversaryYear);
}

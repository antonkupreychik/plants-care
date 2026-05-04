package com.plantcare.bot.repository;

import com.plantcare.bot.domain.Plant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlantRepository extends JpaRepository<Plant, Long> {

    /**
     * Все активные (не архивированные) растения юзера.
     */
    List<Plant> findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(Long userId);

    long countByUserIdAndArchivedAtIsNull(Long userId);
}

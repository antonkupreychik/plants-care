package com.plantcare.bot.repository;

import com.plantcare.bot.domain.Plant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlantRepository extends JpaRepository<Plant, Long> {

    List<Plant> findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(Long userId);

    Optional<Plant> findByUserIdAndIdAndArchivedAtIsNull(Long userId, Long plantId);

    List<Plant> findAllByUserIdAndLocationIdAndArchivedAtIsNullOrderByNameAsc(
            Long userId,
            Long locationId
    );

    long countByUserIdAndArchivedAtIsNull(Long userId);

    long countByUserIdAndLocationIdAndArchivedAtIsNull(Long userId, Long locationId);
}
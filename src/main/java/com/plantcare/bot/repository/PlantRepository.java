package com.plantcare.bot.repository;

import com.plantcare.bot.domain.Plant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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

    /**
     * Сколько растений ссылаются на данный вид.
     * Если в Plant поле называется иначе (например species — @ManyToOne),
     * имя метода нужно подстроить: countBySpecies_Id.
     */
    long countBySpeciesId(Long speciesId);

    /**
     * Batch-вариант: считает растения по списку видов в одном SQL.
     * Используется в админ-списке, чтобы избежать N+1.
     */
    @Query("""
            SELECT p.species.id AS speciesId, COUNT(p) AS plantCount
            FROM Plant p
            WHERE p.species.id  IN :speciesIds
            GROUP BY p.species.id
            """)
    List<SpeciesPlantCount> countBySpeciesIdIn(@Param("speciesIds") Collection<Long> speciesIds);

    /**
     * Spring Data projection для агрегации.
     */
    interface SpeciesPlantCount {
        Long getSpeciesId();
        Long getPlantCount();
    }
}
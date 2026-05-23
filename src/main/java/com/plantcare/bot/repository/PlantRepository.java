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

    // issue #75: acclimation
    // Возвращаем только активные (не архивные) растения — у архивных уведомления
    // в любом случае не шлём, а так избегаем пустой обработки на стороне сервиса.

    @Query("""
            SELECT p FROM Plant p
            WHERE p.archivedAt IS NULL
              AND p.acclimationUntil IS NOT NULL
              AND p.acclimationUntil <= :now
            """)
    List<Plant> findFinishedAcclimation(@Param("now") java.time.LocalDateTime now);

    @Query("""
            SELECT p FROM Plant p
            WHERE p.archivedAt IS NULL
              AND p.acclimationUntil IS NOT NULL
              AND p.acclimationUntil > :now
              AND p.acclimationCheckinNextAt IS NOT NULL
              AND p.acclimationCheckinNextAt <= :now
            """)
    List<Plant> findPendingAcclimationCheckin(@Param("now") java.time.LocalDateTime now);

    /**
     * Фото-прогресс (issue #72): растения, которым пора слать prompt «обнови фото».
     * Условия:
     *   - не архивные;
     *   - фото-прогресс включён ({@code photoProgressFrequency <> OFF});
     *   - {@code next_photo_due_at} ≤ {@code until} (запас вперёд берётся в шедулере);
     *   - дедуп: {@code last_photo_prompt_sent_at IS NULL} либо старше {@code dedupBefore}.
     */
    @Query("""
            SELECT p FROM Plant p
            JOIN FETCH p.user u
            WHERE p.archivedAt IS NULL
              AND p.photoProgressFrequency <> com.plantcare.bot.domain.enums.PhotoProgressFrequency.OFF
              AND p.nextPhotoDueAt IS NOT NULL
              AND p.nextPhotoDueAt <= :until
              AND (p.lastPhotoPromptSentAt IS NULL OR p.lastPhotoPromptSentAt < :dedupBefore)
            """)
    List<Plant> findPhotoProgressDue(
            @Param("until") java.time.LocalDateTime until,
            @Param("dedupBefore") java.time.LocalDateTime dedupBefore
    );
}
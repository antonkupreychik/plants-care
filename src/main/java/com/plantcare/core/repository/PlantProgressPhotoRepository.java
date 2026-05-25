package com.plantcare.core.repository;

import com.plantcare.core.domain.PlantProgressPhoto;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Доступ к таймлайну фото-прогресса (issue #72).
 *
 * <p>Все запросы идут по {@code plant_id} — ownership-проверка происходит
 * выше, в {@link com.plantcare.core.service.PhotoProgressService}, через
 * {@link PlantRepository#findByUserIdAndIdAndArchivedAtIsNull}.
 */
@Repository
public interface PlantProgressPhotoRepository extends JpaRepository<PlantProgressPhoto, Long> {

    List<PlantProgressPhoto> findAllByPlantIdOrderByTakenAtDesc(Long plantId, Pageable pageable);

    long countByPlantId(Long plantId);

    Optional<PlantProgressPhoto> findFirstByPlantIdOrderByTakenAtAsc(Long plantId);

    Optional<PlantProgressPhoto> findFirstByPlantIdOrderByTakenAtDesc(Long plantId);

    /**
     * Найти фото, ближайшее снизу к заданной дате (сравнение «месяц назад vs сейчас»).
     * Через {@link Pageable} ограничиваем размером 1, чтобы получить только одно фото.
     */
    @Query("""
            SELECT p FROM PlantProgressPhoto p
            WHERE p.plant.id = :plantId
              AND p.takenAt <= :before
            ORDER BY p.takenAt DESC
            """)
    List<PlantProgressPhoto> findClosestBefore(
            @Param("plantId") Long plantId,
            @Param("before") LocalDateTime before,
            Pageable pageable
    );

    /**
     * Последние N фото в обратном хронологическом порядке — для экрана «выбрать
     * две даты».
     */
    @Query("""
            SELECT p FROM PlantProgressPhoto p
            WHERE p.plant.id = :plantId
            ORDER BY p.takenAt DESC
            """)
    List<PlantProgressPhoto> findRecent(@Param("plantId") Long plantId, Pageable pageable);

    /**
     * Антиспам: было ли фото за последние N часов.
     */
    boolean existsByPlantIdAndTakenAtAfter(Long plantId, LocalDateTime since);
}

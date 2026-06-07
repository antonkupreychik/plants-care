package com.plantcare.core.repository;

import com.plantcare.core.domain.Plant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
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

    /**
     * Пагинированный список для REST API (issue #85). {@code location},
     * {@code species} и {@code user} подтягиваются через {@link EntityGraph}:
     * {@code location}/{@code species} нужны для {@code PlantController.toDto}
     * вне транзакции (OSIV=false); {@code user} нужен для health-score (issue #207) —
     * {@code HealthScoreService.computeForPlant} читает {@code user.timezone}.
     */
    @EntityGraph(attributePaths = {"location", "species", "user"})
    List<Plant> findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = {"location", "species", "user"})
    List<Plant> findAllByUserIdAndLocationIdAndArchivedAtIsNullOrderByNameAsc(
            Long userId,
            Long locationId,
            Pageable pageable
    );

    Optional<Plant> findByUserIdAndIdAndArchivedAtIsNull(Long userId, Long plantId);

    /**
     * Сколько из переданных {@code ids} — активные (не архивированные) растения
     * данного пользователя. Используется для проверки, что весь набор растений
     * принадлежит владельцу перед выдачей доступа (issue #191): если число
     * меньше размера набора — какой-то id чужой/архивный/несуществующий.
     */
    long countByUserIdAndArchivedAtIsNullAndIdIn(Long userId, Collection<Long> ids);

    /**
     * Растение по id с подтянутыми {@code location}, {@code species} и {@code user}.
     * Используется в {@code PlantService.getPlantOrThrow}, результат которого
     * мапится в DTO вне транзакции (REST API, issue #85) — иначе ленивые
     * связи дают {@code no Session}. {@code LEFT JOIN}, т.к. {@code species}
     * nullable. {@code user} INNER JOIN нужен для сезонных настроек (issue #188) —
     * без eager-загрузки каждый вызов {@code getSchedules}/{@code updateSchedule}
     * делал бы отдельный SELECT.
     */
    @Query("""
            SELECT p FROM Plant p
            JOIN FETCH p.user
            LEFT JOIN FETCH p.location
            LEFT JOIN FETCH p.species
            WHERE p.id = :id
            """)
    Optional<Plant> findByIdWithLocationAndSpecies(@Param("id") Long id);

    List<Plant> findAllByUserIdAndLocationIdAndArchivedAtIsNullOrderByNameAsc(
            Long userId,
            Long locationId
    );

    long countByUserIdAndArchivedAtIsNull(Long userId);

    // ===== Родословная (issue #139, ADR-012) =====

    /**
     * Сколько растений-потомков ссылаются на данное материнское растение.
     * Считает по {@code parent_id} (включая архивированных потомков — каскадной
     * архивации нет, ADR-009). Используется для строки «🌱 Потомки: N» в карточке.
     */
    long countByParentId(Long parentId);

    /**
     * Потомки данного растения (черенки). Не используется в UI напрямую сейчас,
     * но нужен для логики родословной и тестов; экран «дерево» — вне scope #139.
     */
    List<Plant> findByParentId(Long parentId);

    long countByUserIdAndLocationIdAndArchivedAtIsNull(Long userId, Long locationId);

    /** Сколько активных растений привязано к данной комнате (issue #283). */
    long countByRoomIdAndArchivedAtIsNull(Long roomId);

    // ===== Архив (issue #117) =====

    /** Количество архивированных растений юзера — счётчик у кнопки «📦 Архив (N)». */
    long countByUserIdAndArchivedAtIsNotNull(Long userId);

    /**
     * Список архивных растений юзера для экрана «📦 Архив».
     * Сортировка по дате архивации: свежие сверху, чтобы первой шла та,
     * которую только что отправили в архив.
     */
    List<Plant> findAllByUserIdAndArchivedAtIsNotNullOrderByArchivedAtDesc(Long userId);

    /**
     * Архивные растения юзера для REST-экрана «Архив» (issue #219).
     * {@code location}/{@code species} подтягиваются через {@link EntityGraph}:
     * DTO собирается в контроллере вне транзакции (OSIV=false), иначе ленивые
     * связи дадут {@code no Session}. Сортировка — свежие сверху.
     */
    @EntityGraph(attributePaths = {"location", "species"})
    List<Plant> findByUserIdAndArchivedAtIsNotNullOrderByArchivedAtDesc(Long userId);

    /**
     * Архивное растение конкретного юзера по id.
     * Используется в карточке архива и в действиях «восстановить / удалить навсегда».
     */
    Optional<Plant> findByUserIdAndIdAndArchivedAtIsNotNull(Long userId, Long plantId);

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

    // ===== Годовщины (issue #117) =====

    /**
     * Все активные растения (не архивные) с заполненной {@code acquiredAt}.
     * Используется шедулером годовщин для ежедневного обхода: цикл по этому
     * списку дёшев (см. частичный индекс {@code idx_plants_acquired_active}
     * в миграции V21).
     *
     * <p>{@code plant.user} подтягивается через {@code JOIN FETCH}, потому что
     * шедулер обращается к timezone юзера для каждого растения — без fetch будет
     * N+1 загрузок users.
     */
    @Query("""
            SELECT p FROM Plant p
            JOIN FETCH p.user
            WHERE p.acquiredAt IS NOT NULL
              AND p.archivedAt IS NULL
            """)
    List<Plant> findActiveWithAcquiredDate();

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
              AND p.photoProgressFrequency <> com.plantcare.core.domain.enums.PhotoProgressFrequency.OFF
              AND p.nextPhotoDueAt IS NOT NULL
              AND p.nextPhotoDueAt <= :until
              AND (p.lastPhotoPromptSentAt IS NULL OR p.lastPhotoPromptSentAt < :dedupBefore)
            """)
    List<Plant> findPhotoProgressDue(
            @Param("until") java.time.LocalDateTime until,
            @Param("dedupBefore") java.time.LocalDateTime dedupBefore
    );

    // ===== Месячный отчёт (issue #137) =====

    /**
     * Самые старые живые (не-архивные) растения юзера с заполненной
     * {@code acquiredAt} — для блока «самое старое живое растение и его возраст».
     * Сортировка по {@code acquiredAt ASC}: первым идёт растение с самой ранней
     * датой «заведения», то есть самое старое. Вызывающий берёт первый элемент
     * через {@link org.springframework.data.domain.Limit}.
     */
    @Query("""
            SELECT p FROM Plant p
            WHERE p.user.id = :userId
              AND p.archivedAt IS NULL
              AND p.acquiredAt IS NOT NULL
            ORDER BY p.acquiredAt ASC, p.id ASC
            """)
    List<Plant> findOldestLivingByUser(
            @Param("userId") Long userId,
            org.springframework.data.domain.Limit limit
    );

    List<Plant> findAllByUserIdAndParentIdAndArchivedAtIsNullOrderByNameAsc(
            Long userId,
            Long parentId
    );

    // ===== Офлайн-синк (issue #91) =====

    /**
     * Активные (не архивированные и не удалённые) растения юзера, изменённые после {@code since}.
     * {@code location} и {@code species} подтягиваются JOIN FETCH: нужны в DTO вне транзакции.
     */
    @Query("""
            SELECT p FROM Plant p
            LEFT JOIN FETCH p.location
            LEFT JOIN FETCH p.species
            WHERE p.user.id = :userId
              AND p.archivedAt IS NULL
              AND p.deletedAt IS NULL
              AND p.updatedAt > :since
            ORDER BY p.updatedAt ASC
            """)
    List<Plant> findChangedSince(
            @Param("userId") Long userId,
            @Param("since") java.time.LocalDateTime since
    );

    /**
     * Удалённые растения юзера (deletedAt IS NOT NULL), удалённые после {@code since}.
     */
    @Query("""
            SELECT p.id AS id, p.deletedAt AS deletedAt
            FROM Plant p
            WHERE p.user.id = :userId
              AND p.deletedAt IS NOT NULL
              AND p.deletedAt > :since
            """)
    List<DeletedRecord> findDeletedSince(
            @Param("userId") Long userId,
            @Param("since") java.time.LocalDateTime since
    );

    /** Проекция для списка deletions в sync-ответе (issue #91). */
    interface DeletedRecord {
        Long getId();
        java.time.LocalDateTime getDeletedAt();
    }
}
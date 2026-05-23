package com.plantcare.bot.repository;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.enums.TaskType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CareScheduleRepository extends JpaRepository<CareSchedule, Long> {

    Optional<CareSchedule> findByPlantIdAndTaskType(Long plantId, TaskType taskType);

    List<CareSchedule> findAllByPlantId(Long plantId);

    @Query("""
        SELECT s FROM CareSchedule s
        JOIN FETCH s.plant p
        JOIN FETCH p.user u
        WHERE s.active = true
          AND s.nextDueAt <= :now
          AND p.archivedAt IS NULL
          AND u.blocked = false
        ORDER BY s.nextDueAt ASC
        """)
    List<CareSchedule> findDueSchedules(@Param("now") LocalDateTime now);

    /**
     * Для админ-страницы (issue #59): все активные расписания, которые
     * сработают в окне (now; horizon]. В отличие от {@code findDueSchedules}
     * не отсекает уже просроченные — админ должен видеть и их тоже.
     */
    @Query("""
        SELECT s FROM CareSchedule s
        JOIN FETCH s.plant p
        JOIN FETCH p.user u
        WHERE s.active = true
          AND s.nextDueAt <= :horizon
          AND p.archivedAt IS NULL
          AND u.blocked = false
        ORDER BY s.nextDueAt ASC
        """)
    List<CareSchedule> findSchedulesDueBefore(@Param("horizon") LocalDateTime horizon);

    @Query("""
        SELECT s FROM CareSchedule s
        JOIN FETCH s.plant p
        JOIN FETCH p.location l
        WHERE p.user.id = :userId
          AND p.archivedAt IS NULL
          AND s.active = true
          AND s.nextDueAt <= :until
        ORDER BY s.nextDueAt ASC
        """)
    List<CareSchedule> findUserSchedulesDueBefore(
            @Param("userId") Long userId,
            @Param("until") LocalDateTime until
    );

    /**
     * Все активные расписания всех неархивных растений юзера, с прокинутым
     * plant + location, чтобы потом проецировать события в окно календаря
     * (issue #52). nextDueAt не фильтруем — мы проецируем и в прошлое
     * (для overdue) и в будущее.
     */
    @Query("""
        SELECT s FROM CareSchedule s
        JOIN FETCH s.plant p
        JOIN FETCH p.location l
        WHERE p.user.id = :userId
          AND p.archivedAt IS NULL
          AND s.active = true
        ORDER BY p.name ASC, s.taskType ASC
        """)
    List<CareSchedule> findActiveSchedulesByUserId(@Param("userId") Long userId);

    /**
     * Все активные расписания указанного типа в комнате юзера (issue #19, bulk care).
     * JOIN FETCH plant + location, фильтр по user.id обеспечивает ownership-safety:
     * чужой/несуществующий locationId вернёт пустой список без раскрытия деталей.
     */
    @Query("""
        SELECT s FROM CareSchedule s
        JOIN FETCH s.plant p
        JOIN FETCH p.location l
        WHERE p.user.id = :userId
          AND p.location.id = :locationId
          AND p.archivedAt IS NULL
          AND s.active = true
          AND s.taskType = :taskType
        ORDER BY p.name ASC
        """)
    List<CareSchedule> findActiveSchedulesInUserLocation(
            @Param("userId") Long userId,
            @Param("locationId") Long locationId,
            @Param("taskType") TaskType taskType
    );

    /**
     * Existence-проверка для решения «показывать ли кнопку Полить-все» в карточке локации.
     * Те же фильтры, что у bulk-метода — гарантируют 1-to-1 соответствие между видимостью
     * кнопки и тем, что bulk-операция реально что-то сделает.
     */
    @Query("""
        SELECT COUNT(s) > 0 FROM CareSchedule s
        WHERE s.plant.user.id = :userId
          AND s.plant.location.id = :locationId
          AND s.plant.archivedAt IS NULL
          AND s.active = true
          AND s.taskType = :taskType
        """)
    boolean hasActiveSchedulesInUserLocation(
            @Param("userId") Long userId,
            @Param("locationId") Long locationId,
            @Param("taskType") TaskType taskType
    );

    /**
     * Все просроченные активные расписания юзера (issue #53, welcome-back список).
     * JOIN FETCH plant — потому что в результирующем экране нужно имя растения,
     * вне открытой сессии.
     */
    @Query("""
        SELECT s FROM CareSchedule s
        JOIN FETCH s.plant p
        WHERE p.user.id = :userId
          AND p.archivedAt IS NULL
          AND s.active = true
          AND s.nextDueAt <= :asOf
        ORDER BY s.nextDueAt ASC
        """)
    List<CareSchedule> findOverdueForUser(
            @Param("userId") Long userId,
            @Param("asOf") LocalDateTime asOf
    );
}
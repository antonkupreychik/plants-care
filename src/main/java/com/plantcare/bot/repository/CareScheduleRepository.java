package com.plantcare.bot.repository;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.enums.TaskType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CareScheduleRepository extends JpaRepository<CareSchedule, Long> {

    /**
     * Расписание заданного типа для растения. Гарантировано не более одного
     * благодаря UNIQUE (plant_id, task_type).
     */
    Optional<CareSchedule> findByPlantIdAndTaskType(Long plantId, TaskType taskType);

    List<CareSchedule> findAllByPlantId(Long plantId);

    /**
     * Активные расписания, время которых наступило. Используется шедулером уведомлений (#10).
     * JOIN FETCH grunt'ит растение и юзера одним запросом — иначе шедулер словит N+1.
     */
    @Query("""
        SELECT s FROM CareSchedule s
        JOIN FETCH s.plant p
        JOIN FETCH p.user u
        WHERE s.active = true
          AND s.nextDueAt <= :now
          AND p.archivedAt IS NULL
          AND u.blocked = false
        """)
    List<CareSchedule> findDueSchedules(@Param("now") LocalDateTime now);

    /**
     * Расписания юзера с просроченным или сегодняшним dueAt.
     * Используется в /menu и в команде "что нужно сегодня" (#12).
     */
    @Query("""
        SELECT s FROM CareSchedule s
        JOIN FETCH s.plant p
        WHERE p.user.id = :userId
          AND p.archivedAt IS NULL
          AND s.active = true
          AND s.nextDueAt <= :until
        ORDER BY s.nextDueAt ASC
        """)
    List<CareSchedule> findUserSchedulesDueBefore(@Param("userId") Long userId,
                                                   @Param("until") LocalDateTime until);
}

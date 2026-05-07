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
}
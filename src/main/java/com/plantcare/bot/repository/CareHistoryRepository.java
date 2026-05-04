package com.plantcare.bot.repository;

import com.plantcare.bot.domain.CareHistory;
import com.plantcare.bot.domain.enums.TaskType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CareHistoryRepository extends JpaRepository<CareHistory, Long> {

    List<CareHistory> findAllByPlantIdOrderByDoneAtDesc(Long plantId, Limit limit);

    /**
     * Последняя запись истории для растения по конкретному типу задачи.
     * Используется для idempotency-проверки при отметке "сделано" (#11):
     * если только что был такой же done — считаем дубликатом, не дублируем.
     */
    Optional<CareHistory> findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(
            Long plantId, TaskType taskType);

    /**
     * Сколько раз растению делали задачу с указанной даты.
     * Понадобится для статистики (#этап 3).
     */
    long countByPlantIdAndTaskTypeAndDoneAtAfter(
            Long plantId, TaskType taskType, LocalDateTime after);
}

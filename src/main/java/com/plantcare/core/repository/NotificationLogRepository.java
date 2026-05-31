package com.plantcare.core.repository;

import com.plantcare.core.domain.NotificationLog;
import com.plantcare.core.domain.enums.TaskType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    /**
     * Проверка, было ли отправлено уведомление за указанный период.
     * Используется шедулером (#10) для дедупликации: не слать повторное
     * уведомление, если за последние 12 часов уже отправляли.
     */
    boolean existsByPlantIdAndTaskTypeAndSentAtAfter(
            Long plantId, TaskType taskType, LocalDateTime after);
}

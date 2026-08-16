package com.plantcare.core.repository;

import com.plantcare.core.domain.NotificationDeliveryEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Запись журнала доставок (issue #95). Читает журнал дашборд — агрегатами через
 * {@code AdminNotificationHealthRepository}, а не через эту сущность.
 */
@Repository
public interface NotificationDeliveryEventRepository extends JpaRepository<NotificationDeliveryEvent, Long> {

    /**
     * Удалить события старше {@code cutoff} (ретенция). Идемпотентно: повторный
     * вызов удалит 0 строк.
     *
     * @return число удалённых строк
     */
    @Modifying
    @Query("DELETE FROM NotificationDeliveryEvent e WHERE e.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}

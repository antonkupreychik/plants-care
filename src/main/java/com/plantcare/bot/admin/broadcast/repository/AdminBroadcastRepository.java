package com.plantcare.bot.admin.broadcast.repository;

import com.plantcare.bot.domain.AdminBroadcast;
import com.plantcare.bot.domain.enums.BroadcastStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AdminBroadcastRepository extends JpaRepository<AdminBroadcast, Long> {

    /**
     * Есть ли сейчас RUNNING broadcast? Защита от двойного запуска.
     * Используем {@code findFirst}, потому что параллельных RUNNING быть не должно.
     */
    Optional<AdminBroadcast> findFirstByStatus(BroadcastStatus status);

    /** Последние N broadcast-ов для лога снизу страницы. */
    List<AdminBroadcast> findAllByOrderByCreatedAtDesc(Limit limit);

    /**
     * Атомарный инкремент счётчиков — единственный безопасный способ обновить
     * progress, когда страница может читать запись параллельно. UPDATE по id,
     * без оптимистической блокировки и без перезагрузки.
     */
    @Modifying
    @Query("""
        UPDATE AdminBroadcast b
           SET b.sentCount    = b.sentCount    + :sent,
               b.failedCount  = b.failedCount  + :failed,
               b.blockedCount = b.blockedCount + :blocked
         WHERE b.id = :id
        """)
    int bumpCounters(@Param("id") Long id,
                     @Param("sent") int sent,
                     @Param("failed") int failed,
                     @Param("blocked") int blocked);

    /**
     * Финализация: переводим в терминальное состояние и фиксируем finished_at.
     * Отдельный запрос, чтобы не делать find→save (даёт ОПтиматических-lock проблемы
     * при параллельном UPDATE из bumpCounters).
     */
    @Modifying
    @Query("""
        UPDATE AdminBroadcast b
           SET b.status = :status, b.finishedAt = :finishedAt
         WHERE b.id = :id
        """)
    int markFinished(@Param("id") Long id,
                     @Param("status") BroadcastStatus status,
                     @Param("finishedAt") LocalDateTime finishedAt);

    /**
     * Отдельный UPDATE для перехода RUNNING → STOPPED по запросу пользователя.
     * Возвращает 1 если действительно остановили, 0 если broadcast уже завершён
     * (между нажатием «Остановить» и обработкой бэкендом).
     */
    @Modifying
    @Query("""
        UPDATE AdminBroadcast b
           SET b.status = :stopped
         WHERE b.id = :id AND b.status = :running
        """)
    int requestStop(@Param("id") Long id,
                    @Param("running") BroadcastStatus running,
                    @Param("stopped") BroadcastStatus stopped);
}

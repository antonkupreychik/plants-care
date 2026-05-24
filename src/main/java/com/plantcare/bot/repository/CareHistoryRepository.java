package com.plantcare.bot.repository;

import com.plantcare.bot.domain.CareHistory;
import com.plantcare.bot.domain.enums.TaskType;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CareHistoryRepository extends JpaRepository<CareHistory, Long> {

    List<CareHistory> findAllByPlantIdOrderByDoneAtDesc(Long plantId, Limit limit);

    /**
     * Постраничный листинг истории растения для экрана «📜 История».
     * Активный (не-cancelled) фильтр включён, чтобы не показывать перекрытые записи.
     */
    @Query("SELECT h FROM CareHistory h " +
            "WHERE h.plant.id = :plantId AND h.cancelledBy IS NULL " +
            "ORDER BY h.doneAt DESC")
    List<CareHistory> findActiveByPlantIdPaged(@Param("plantId") Long plantId, Pageable pageable);

    /**
     * Кол-во активных записей истории — нужно для пагинатора (X / N).
     */
    @Query("SELECT COUNT(h) FROM CareHistory h " +
            "WHERE h.plant.id = :plantId AND h.cancelledBy IS NULL")
    long countActiveByPlantId(@Param("plantId") Long plantId);

    /**
     * Кол-во активных записей по растению за период (для on-time-% за 30 дней).
     */
    @Query("SELECT COUNT(h) FROM CareHistory h " +
            "WHERE h.plant.id = :plantId AND h.cancelledBy IS NULL " +
            "AND h.doneAt > :after")
    long countActiveByPlantIdSince(@Param("plantId") Long plantId,
                                   @Param("after") LocalDateTime after);

    /**
     * Кол-во on-time записей по растению за период (числитель для on-time-%).
     */
    @Query("SELECT COUNT(h) FROM CareHistory h " +
            "WHERE h.plant.id = :plantId AND h.cancelledBy IS NULL " +
            "AND h.onTime = true AND h.doneAt > :after")
    long countActiveOnTimeByPlantIdSince(@Param("plantId") Long plantId,
                                         @Param("after") LocalDateTime after);

    /**
     * Все done_at для всех растений юзера (для расчёта user-streak по дням).
     * Активные записи (не-cancelled). Сортировка по убыванию.
     */
    @Query("SELECT h.doneAt FROM CareHistory h " +
            "WHERE h.plant.user.id = :userId AND h.cancelledBy IS NULL " +
            "ORDER BY h.doneAt DESC")
    List<LocalDateTime> findUserDoneAtsDesc(@Param("userId") Long userId, Limit limit);

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

    /**
     * Поиск по client_id для идемпотентности REST API (issue #86).
     * client_id генерирует мобильный клиент при POST /api/v1/care-events;
     * повторный запрос с тем же client_id вернёт уже существующую запись.
     */
    Optional<CareHistory> findByClientId(String clientId);

    /**
     * Существует ли активная (не-cancelled) запись ухода данного типа за период.
     * Используется для идемпотентности ретро-отметки (issue #118): юзер второй раз
     * жмёт «отметить вчера» — не плодим дубликат, говорим «уже отмечено».
     *
     * <p>Окно вызывающий передаёт как «начало/конец локального дня в TZ юзера,
     * сконвертированные в UTC».
     */
    @Query("SELECT COUNT(h) > 0 FROM CareHistory h " +
            "WHERE h.plant.id = :plantId AND h.taskType = :taskType " +
            "AND h.cancelledBy IS NULL " +
            "AND h.doneAt >= :from AND h.doneAt < :toExclusive")
    boolean existsActiveByPlantIdAndTaskTypeInRange(
            @Param("plantId") Long plantId,
            @Param("taskType") TaskType taskType,
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive
    );

    /**
     * Постраничный листинг истории с реальным offset (не page-based).
     *
     * <p>Используется REST API GET /api/v1/plants/{id}/history?limit=N&offset=M.
     * Нативный запрос нужен потому, что Spring Data {@link org.springframework.data.domain.PageRequest}
     * вычисляет смещение как page * size — при offset=5, limit=20 это даёт page=0, что неверно.
     *
     * @param plantId id растения
     * @param limit   количество записей
     * @param offset  смещение от начала (0-based)
     * @return срез истории
     */
    @Query(value = "SELECT * FROM care_history h " +
            "WHERE h.plant_id = :plantId AND h.cancelled_by IS NULL " +
            "ORDER BY h.done_at DESC " +
            "LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<CareHistory> findActiveByPlantIdWithRealOffset(
            @Param("plantId") Long plantId,
            @Param("limit") int limit,
            @Param("offset") int offset
    );
}

package com.plantcare.core.repository;

import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.enums.TaskType;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
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
     * Сколько раз растению делали задачу за всё время (issue #117 — статистика
     * в карточке архива «N поливов за M месяцев», тексте годовщины
     * «полил её N раз»). Активные (не-cancelled) записи.
     */
    @Query("SELECT COUNT(h) FROM CareHistory h " +
            "WHERE h.plant.id = :plantId AND h.taskType = :taskType " +
            "AND h.cancelledBy IS NULL")
    long countActiveByPlantIdAndTaskType(
            @Param("plantId") Long plantId,
            @Param("taskType") TaskType taskType);

    /**
     * Поиск по client_id для идемпотентности REST API (issue #86).
     * client_id генерирует мобильный клиент при POST /api/v1/care-events;
     * повторный запрос с тем же client_id вернёт уже существующую запись.
     *
     * <p>{@code plant} подтягивается через {@link EntityGraph}: запись из этой
     * ветки сразу мапится в {@code CareEventResponse} вне транзакции (OSIV=false),
     * а маппинг читает {@code plant.getName()} — без графа был бы {@code no Session}.
     */
    @EntityGraph(attributePaths = "plant")
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
     * Уникальные пользователи, у которых был хотя бы один активный {@code care_event}
     * за окно {@code (after; now]}. Используется метрикой DAU (issue #115).
     *
     * <p>Запрос идёт по индексу {@code care_history(done_at)} — окно 24h
     * с типичной нагрузкой укладывается в десятки мс. Зовётся раз в час
     * из {@code DauMetricsUpdater}, поэтому даже на больших данных не hot path.
     */
    @Query("SELECT COUNT(DISTINCT h.plant.user.id) FROM CareHistory h " +
            "WHERE h.cancelledBy IS NULL AND h.doneAt > :after")
    long countDistinctActiveUsersSince(@Param("after") LocalDateTime after);

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

    /**
     * Постраничный листинг истории с реальным offset и фильтром по типу ухода.
     *
     * <p>Используется REST API GET /api/v1/plants/{id}/history?type=WATERING&limit=N&offset=M.
     * Нативный запрос аналогичен {@link #findActiveByPlantIdWithRealOffset}, но добавляет
     * опциональный фильтр по типу: если {@code taskType} передан как {@code null} —
     * условие игнорируется и возвращаются все типы.
     *
     * @param plantId  id растения
     * @param limit    количество записей
     * @param offset   смещение от начала (0-based)
     * @param taskType {@code TaskType.name()} для фильтра, или {@code null} — все типы
     * @return срез истории
     */
    @Query(value = "SELECT * FROM care_history h " +
            "WHERE h.plant_id = :plantId AND h.cancelled_by IS NULL " +
            "AND (:taskType IS NULL OR h.task_type = :taskType) " +
            "ORDER BY h.done_at DESC " +
            "LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<CareHistory> findActiveByPlantIdWithRealOffsetAndType(
            @Param("plantId") Long plantId,
            @Param("limit") int limit,
            @Param("offset") int offset,
            @Param("taskType") String taskType
    );

    /**
     * Кол-во активных (не-cancelled) записей с опциональным фильтром по типу ухода.
     *
     * <p>Если {@code taskType} передан как {@code null} — возвращает общее число,
     * аналогично {@link #countActiveByPlantId}.
     */
    @Query(value = "SELECT COUNT(*) FROM care_history h " +
            "WHERE h.plant_id = :plantId AND h.cancelled_by IS NULL " +
            "AND (:taskType IS NULL OR h.task_type = :taskType)", nativeQuery = true)
    long countActiveByPlantIdAndType(
            @Param("plantId") Long plantId,
            @Param("taskType") String taskType
    );

    /**
     * Кол-во активных (не-cancelled) on-time записей по растению за всё время.
     * Числитель для {@code onTimePercent} в сводке истории (issue #206).
     */
    @Query("SELECT COUNT(h) FROM CareHistory h " +
            "WHERE h.plant.id = :plantId AND h.cancelledBy IS NULL " +
            "AND h.onTime = true")
    long countActiveOnTimeByPlantId(@Param("plantId") Long plantId);

    /**
     * Разбивка активных (не-cancelled) записей по типам ухода за всё время.
     * Только типы с count ≥ 1 попадают в результат (GROUP BY без HAVING — фильтрация в сервисе).
     * Используется для {@code byType} в сводке истории (issue #206).
     */
    @Query("SELECT h.taskType AS taskType, COUNT(h) AS count FROM CareHistory h " +
            "WHERE h.plant.id = :plantId AND h.cancelledBy IS NULL " +
            "GROUP BY h.taskType")
    List<TaskTypeCount> countActiveByPlantIdGroupedByType(@Param("plantId") Long plantId);

    // ===== Месячный отчёт (issue #137) =====

    /**
     * Всего активных (не-cancelled) действий ухода по всем растениям юзера за
     * отчётный период {@code [from; toExclusive)}. Границы передаёт вызывающий —
     * это «начало/конец месяца в TZ юзера, сконвертированные в UTC».
     */
    @Query("SELECT COUNT(h) FROM CareHistory h " +
            "WHERE h.plant.user.id = :userId AND h.cancelledBy IS NULL " +
            "AND h.doneAt >= :from AND h.doneAt < :toExclusive")
    long countActiveByUserIdInRange(
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive
    );

    /**
     * Разбивка активных действий юзера по типам ухода за отчётный период
     * {@code [from; toExclusive)}. Только типы с ≥1 действием попадают в результат.
     */
    @Query("SELECT h.taskType AS taskType, COUNT(h) AS count FROM CareHistory h " +
            "WHERE h.plant.user.id = :userId AND h.cancelledBy IS NULL " +
            "AND h.doneAt >= :from AND h.doneAt < :toExclusive " +
            "GROUP BY h.taskType")
    List<TaskTypeCount> countActiveByUserIdGroupedByTaskType(
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive
    );

    /**
     * «Растение, которое юзер ни разу не пропустил» за отчётный период: среди
     * активных не-архивных растений выбираются те, у которых ВСЕ активные записи
     * за период были on-time ({@code was_on_time = true}) и записей ≥1. Из них
     * берётся растение с наибольшим числом действий (tie-break — меньший id).
     *
     * <p>Условие «все on-time» выражено через
     * {@code MIN(was_on_time) = true} в HAVING: если хоть одна запись была не
     * on-time, минимум по группе станет false и группа отсеется.
     */
    @Query("SELECT h.plant.id AS plantId, h.plant.name AS plantName, COUNT(h) AS count " +
            "FROM CareHistory h " +
            "WHERE h.plant.user.id = :userId AND h.cancelledBy IS NULL " +
            "AND h.plant.archivedAt IS NULL " +
            "AND h.doneAt >= :from AND h.doneAt < :toExclusive " +
            "GROUP BY h.plant.id, h.plant.name " +
            "HAVING MIN(CASE WHEN h.onTime = true THEN 1 ELSE 0 END) = 1 " +
            "ORDER BY COUNT(h) DESC, h.plant.id ASC")
    List<PlantActionCount> findFlawlessPlantsByUserInRange(
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive,
            Limit limit
    );

    // ===== Прогресс выполнения по дням (issue #179, gap G11) =====

    /**
     * {@code done_at} всех активных (не-cancelled) записей юзера за окно
     * {@code [from; toExclusive)} — для агрегации прогресса по локальным дням
     * в {@code GET /api/v1/calendar/progress}. Бакетирование по TZ юзера
     * выполняется в сервисе, поэтому возвращаем сырые UTC-метки.
     *
     * <p>Границы — «начало/конец локального дня в TZ юзера, сконвертированные в UTC».
     */
    @Query("SELECT h.doneAt FROM CareHistory h " +
            "WHERE h.plant.user.id = :userId AND h.cancelledBy IS NULL " +
            "AND h.doneAt >= :from AND h.doneAt < :toExclusive")
    List<LocalDateTime> findActiveDoneAtsByUserIdInRange(
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive
    );

    // ===== Месячный отчёт REST API (issue #192, gap G23) =====

    /**
     * Кол-во активных (не-cancelled) действий ухода юзера за период
     * {@code [from; toExclusive)}, выполненных с опозданием ({@code was_on_time = false}).
     * Числитель «overdue» месячного отчёта. Границы — UTC LocalDateTime
     * (начало/конец месяца в TZ юзера, сконвертированные в UTC).
     */
    @Query("SELECT COUNT(h) FROM CareHistory h " +
            "WHERE h.plant.user.id = :userId AND h.cancelledBy IS NULL " +
            "AND h.onTime = false " +
            "AND h.doneAt >= :from AND h.doneAt < :toExclusive")
    long countLateActiveByUserIdInRange(
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive
    );

    /**
     * Пары {@code (done_at, was_on_time)} всех активных (не-cancelled) действий
     * юзера за период {@code [from; toExclusive)} — для понедельного тренда
     * качества месячного отчёта (issue #192). Бакетинг по ISO-неделе и в TZ
     * юзера выполняется в Java, поэтому из БД тянем сырые точки одним запросом.
     */
    @Query("SELECT h.doneAt AS doneAt, h.onTime AS onTime FROM CareHistory h " +
            "WHERE h.plant.user.id = :userId AND h.cancelledBy IS NULL " +
            "AND h.doneAt >= :from AND h.doneAt < :toExclusive")
    List<DoneAtOnTime> findActiveDoneAtOnTimeByUserIdInRange(
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive
    );

    /** Проекция «момент выполнения → on-time» для понедельного тренда (issue #192). */
    interface DoneAtOnTime {
        LocalDateTime getDoneAt();
        boolean getOnTime();
    }

    // ===== Done-фид для /today (issue #184, ADR-014) =====

    /**
     * Ключи задач, отмеченных «сделано» за окно сегодняшнего дня (issue #184).
     * Возвращает по одной строке на пару (plantId, taskType) с максимальным
     * {@code doneAt} в окне — берётся самый поздний момент отметки.
     *
     * <p>Только активные (не-cancelled) записи неархивных растений юзера.
     * Границы окна {@code [from; to]} — UTC LocalDateTime, передаёт сервис
     * (начало/конец локального дня в TZ юзера, сконвертированные в UTC).
     */
    @Query("SELECT h.plant.id AS plantId, h.taskType AS taskType, MAX(h.doneAt) AS doneAt " +
            "FROM CareHistory h " +
            "WHERE h.plant.user.id = :userId AND h.cancelledBy IS NULL " +
            "AND h.plant.archivedAt IS NULL " +
            "AND h.doneAt >= :from AND h.doneAt <= :to " +
            "GROUP BY h.plant.id, h.taskType")
    List<DoneTaskKey> findDoneTaskKeysInRange(
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    /** Проекция «(plant, taskType) → момент отметки» для done-фида /today (issue #184). */
    interface DoneTaskKey {
        Long getPlantId();
        TaskType getTaskType();
        LocalDateTime getDoneAt();
    }

    /** Проекция «тип ухода → количество» для разбивки месячного отчёта (issue #137). */
    interface TaskTypeCount {
        TaskType getTaskType();
        long getCount();
    }

    /** Проекция «растение → количество действий» для месячного отчёта (issue #137). */
    interface PlantActionCount {
        Long getPlantId();
        String getPlantName();
        long getCount();
    }
}

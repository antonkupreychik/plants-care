package com.plantcare.bot.repository;

import com.plantcare.bot.domain.PlantEvent;
import com.plantcare.bot.domain.enums.PlantEventType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlantEventRepository extends JpaRepository<PlantEvent, Long> {

    /**
     * Постранично — последние события растения (DESC по event_date).
     * Используется журналом (issue #76).
     */
    Page<PlantEvent> findByPlantIdOrderByEventDateDesc(Long plantId, Pageable pageable);

    /**
     * Последнее событие данного типа — для дедупа двойных нажатий.
     */
    Optional<PlantEvent> findFirstByPlantIdAndEventTypeOrderByEventDateDesc(
            Long plantId, PlantEventType eventType
    );

    /**
     * Общее число событий растения — для тех мест UI, где нужно показать
     * счётчик/наличие журнала без загрузки самих записей.
     */
    long countByPlantId(Long plantId);

    /**
     * Последние события заданного типа по всем активным (не архивным) растениям —
     * источник «предстоящих пересадок» для подсказки расходников (issue #141).
     *
     * <p>Возвращает ровно по одной записи на растение — последнюю по
     * {@code event_date}, а при равных {@code event_date} (импорт журнала,
     * бэкфил, двойной тап) — с максимальным {@code id} (большее id = создано
     * позже). Тай-брейк по {@code id} обязателен: иначе подзапрос вернул бы обе
     * строки с одинаковым MAX(event_date) и юзер получил бы две подсказки про
     * одно растение (UNIQUE по триплету их не схлопнет — source_event_id разные).
     *
     * <p>Отбор сведён к одному {@code id} на растение: внутренний подзапрос ищет
     * MAX(event_date) этого типа для растения, внешний — MAX(id) среди строк с
     * этим MAX(event_date). Так {@code WHERE e.id = (...)} гарантирует одну
     * строку и сохраняет {@code JOIN FETCH} (нативка с fetch join не дружит).
     *
     * <p>{@code JOIN FETCH} тянет {@code plant} и его {@code user}, потому что
     * шедулер для каждого кандидата читает имя растения и timezone юзера — без
     * fetch будет N+1.
     *
     * <p>Архивные растения исключены: подсказывать пересадку по убранному в
     * архив растению смысла нет.
     */
    @Query("""
            SELECT e FROM PlantEvent e
            JOIN FETCH e.plant p
            JOIN FETCH p.user
            WHERE e.eventType = :eventType
              AND p.archivedAt IS NULL
              AND e.id = (
                  SELECT MAX(e2.id) FROM PlantEvent e2
                  WHERE e2.plant = e.plant
                    AND e2.eventType = :eventType
                    AND e2.eventDate = (
                        SELECT MAX(e3.eventDate) FROM PlantEvent e3
                        WHERE e3.plant = e.plant
                          AND e3.eventType = :eventType
                    )
              )
            """)
    List<PlantEvent> findLatestEventPerActivePlant(@Param("eventType") PlantEventType eventType);
}

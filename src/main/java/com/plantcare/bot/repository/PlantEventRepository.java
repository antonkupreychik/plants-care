package com.plantcare.bot.repository;

import com.plantcare.bot.domain.PlantEvent;
import com.plantcare.bot.domain.enums.PlantEventType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}

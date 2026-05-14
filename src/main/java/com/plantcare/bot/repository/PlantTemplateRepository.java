package com.plantcare.bot.repository;

import com.plantcare.bot.domain.PlantTemplate;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlantTemplateRepository extends JpaRepository<PlantTemplate, Long> {

    /**
     * Список шаблонов для UI. EntityGraph подгружает careRules сразу, чтобы
     * shortDescription() работал из state-handler'ов и MenuCallbackService
     * после выхода из @Transactional метода сервиса (issue #68).
     */
    @EntityGraph(attributePaths = "careRules")
    List<PlantTemplate> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * Получение шаблона с careRules — нужен для создания растения из шаблона
     * и любых других мест, где могут понадобиться правила вне транзакции.
     */
    @EntityGraph(attributePaths = "careRules")
    Optional<PlantTemplate> findByUserIdAndId(Long userId, Long templateId);

    long countByUserId(Long userId);

    /**
     * Case-insensitive проверка дубля имени. Зеркалит индекс
     * uq_plant_templates_user_lower_name из миграции V8.
     */
    boolean existsByUserIdAndNameIgnoreCase(Long userId, String name);
}

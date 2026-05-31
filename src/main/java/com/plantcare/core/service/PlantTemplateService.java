package com.plantcare.core.service;

import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.PlantTemplate;
import com.plantcare.core.domain.PlantTemplateCareRule;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.PlantTemplateCareRuleRepository;
import com.plantcare.core.repository.PlantTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Бизнес-логика пользовательских шаблонов растений (issue #68).
 *
 * <p><b>Что копируем в шаблон:</b> только активные интервалы ухода
 * (WATERING / MISTING / FERTILIZING) — требование issue #68.
 * Фото, заметки, локацию, дату последнего ухода — НЕ копируем (по issue).
 *
 * <p><b>Удаление шаблона</b> не каскадирует на растения (FK от plants нет).
 *
 * <p><b>Лимиты:</b> 30 шаблонов на пользователя, имя 1–40 символов (трим).
 *
 * <p><b>Дубли имён:</b> запрещены case-insensitive (зафиксированный
 * вариант из двух предложенных в issue #68).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlantTemplateService {

    public static final int MAX_TEMPLATES_PER_USER = 30;
    public static final int MAX_NAME_LENGTH = 40;

    private final PlantTemplateRepository templateRepository;
    private final PlantTemplateCareRuleRepository careRuleRepository;
    private final PlantService plantService;
    private final CareScheduleRepository careScheduleRepository;
    private final com.plantcare.core.seasonal.service.SeasonalIntervalService seasonalIntervalService;

    // =================================================================
    // saveFromPlant — сохранить шаблон из существующего растения
    // =================================================================

    /**
     * Создаёт шаблон из активных расписаний существующего растения.
     *
     * @throws IllegalStateException    если достигнут лимит {@value #MAX_TEMPLATES_PER_USER}
     * @throws IllegalArgumentException если имя невалидно или дублирует существующее
     */
    @Transactional
    public PlantTemplate saveFromPlant(User user, Long plantId, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        validateName(name);

        if (templateRepository.countByUserId(user.getId()) >= MAX_TEMPLATES_PER_USER) {
            throw new IllegalStateException(
                    "Достигнут лимит " + MAX_TEMPLATES_PER_USER + " шаблонов. " +
                    "Удали неиспользуемые в ⚙️ Настройки → ⭐ Мои шаблоны.");
        }

        if (templateRepository.existsByUserIdAndNameIgnoreCase(user.getId(), name)) {
            throw new IllegalArgumentException(
                    "Шаблон с именем «" + name + "» уже существует. Выбери другое название.");
        }

        PlantTemplate template = PlantTemplate.builder()
                .userId(user.getId())
                .name(name)
                .build();
        template = templateRepository.save(template);

        // Копируем только активные расписания — отключённые тумблером не нужны
        List<CareSchedule> activeSchedules = careScheduleRepository
                .findAllByPlantId(plantId).stream()
                .filter(CareSchedule::isActive)
                .toList();

        final PlantTemplate savedTemplate = template;
        for (CareSchedule schedule : activeSchedules) {
            PlantTemplateCareRule rule = PlantTemplateCareRule.builder()
                    .template(savedTemplate)
                    .careType(schedule.getTaskType())
                    .intervalDays(schedule.getIntervalDays())
                    .build();
            careRuleRepository.save(rule);
        }

        log.info("Saved template '{}' (id={}) from plant {} for user {}",
                name, template.getId(), plantId, user.getId());
        return template;
    }

    // =================================================================
    // createPlantFromTemplate — создать новое растение из шаблона
    // =================================================================

    /**
     * Создаёт новое растение, копируя интервалы из шаблона. Локация — дефолтная
     * для пользователя (через PlantService#createPlantWithWateringSchedule с
     * locationId=null фоллбэчится на getOrCreateDefaultLocation).
     */
    @Transactional
    public Plant createPlantFromTemplate(User user, Long templateId, String rawPlantName) {
        PlantTemplate template = templateRepository.findByUserIdAndId(user.getId(), templateId)
                .orElseThrow(() -> new IllegalArgumentException("Шаблон не найден"));

        String plantName = rawPlantName == null ? "" : rawPlantName.trim();
        if (plantName.isBlank()) {
            throw new IllegalArgumentException("Имя растения не может быть пустым");
        }

        // Ищем правило полива (обязательное для создания через PlantService);
        // если в шаблоне нет — дефолт 7 дней (как и при custom species).
        int wateringInterval = template.getCareRules().stream()
                .filter(r -> r.getCareType() == TaskType.WATERING)
                .findFirst()
                .map(PlantTemplateCareRule::getIntervalDays)
                .orElse(7);

        // Применяем сезонную корректировку (issue #67). Для нового растения
        // seasonalOverride = INHERIT (builder default в Plant), значит решает
        // только глобальная настройка юзера — Plant даже не нужен, но передаём
        // фиктивный с INHERIT чтобы не плодить overload в API.
        com.plantcare.core.domain.Plant probe = com.plantcare.core.domain.Plant.builder()
                .user(user)
                .seasonalOverride(com.plantcare.core.domain.enums.SeasonalOverride.INHERIT)
                .build();
        int effectiveWatering = seasonalIntervalService.effectiveIntervalDays(
                probe, user, wateringInterval);
        LocalDateTime nextWateringAt = LocalDateTime.now().plusDays(effectiveWatering);

        Plant plant = plantService.createPlantWithWateringSchedule(
                user, null, plantName, wateringInterval, nextWateringAt);

        // Добавляем остальные расписания (MISTING, FERTILIZING) если они есть в шаблоне
        for (PlantTemplateCareRule rule : template.getCareRules()) {
            if (rule.getCareType() != TaskType.WATERING) {
                int effective = seasonalIntervalService.effectiveIntervalDays(
                        plant, user, rule.getIntervalDays());
                plantService.addCareSchedule(
                        plant,
                        rule.getCareType(),
                        rule.getIntervalDays(),
                        LocalDateTime.now().plusDays(effective)
                );
            }
        }

        log.info("Created plant '{}' (id={}) from template '{}' for user {}",
                plantName, plant.getId(), template.getName(), user.getId());
        return plant;
    }

    // =================================================================
    // Управление: список / переименование / удаление
    // =================================================================

    @Transactional(readOnly = true)
    public List<PlantTemplate> getUserTemplates(Long userId) {
        return templateRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Optional<PlantTemplate> getTemplate(Long userId, Long templateId) {
        return templateRepository.findByUserIdAndId(userId, templateId);
    }

    @Transactional
    public PlantTemplate renameTemplate(User user, Long templateId, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        validateName(name);

        PlantTemplate template = templateRepository.findByUserIdAndId(user.getId(), templateId)
                .orElseThrow(() -> new IllegalArgumentException("Шаблон не найден"));

        // Если меняется регистр — позволяем; если новое имя совпадает с другим — запрет
        if (!template.getName().equalsIgnoreCase(name) &&
                templateRepository.existsByUserIdAndNameIgnoreCase(user.getId(), name)) {
            throw new IllegalArgumentException(
                    "Шаблон с именем «" + name + "» уже существует. Выбери другое название.");
        }

        template.setName(name);
        log.info("Renamed template {} to '{}' for user {}", templateId, name, user.getId());
        return templateRepository.save(template);
    }

    @Transactional
    public void deleteTemplate(User user, Long templateId) {
        PlantTemplate template = templateRepository.findByUserIdAndId(user.getId(), templateId)
                .orElseThrow(() -> new IllegalArgumentException("Шаблон не найден"));
        templateRepository.delete(template);
        log.info("Deleted template {} for user {}", templateId, user.getId());
    }

    @Transactional(readOnly = true)
    public boolean hasReachedLimit(Long userId) {
        return templateRepository.countByUserId(userId) >= MAX_TEMPLATES_PER_USER;
    }

    // =================================================================
    // Валидация (статический метод — вызывается из state-handler'ов)
    // =================================================================

    public static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Название шаблона не может быть пустым.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Название шаблона не должно превышать " + MAX_NAME_LENGTH + " символов.");
        }
    }
}

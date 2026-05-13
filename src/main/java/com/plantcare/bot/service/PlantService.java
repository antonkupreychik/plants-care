package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.Species;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareScheduleRepository;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.repository.SpeciesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlantService {

    private final PlantRepository plantRepository;
    private final CareScheduleRepository careScheduleRepository;
    private final SpeciesRepository speciesRepository;
    private final LocationService locationService;

    /**
     * Получить топ популярных видов для отображения на первом экране.
     */
    @Transactional(readOnly = true)
    public List<Species> getPopularSpecies(int limit) {
        return speciesRepository.findAllByOrderByPopularityDesc(
                Limit.of(limit)
        );
    }

    /**
     * Поиск видов по названию или тегам.
     */
    @Transactional(readOnly = true)
    public List<Species> searchSpecies(String query, int limit) {
        return speciesRepository.searchByQuery(query, limit);
    }

    /**
     * Получить вид по ID.
     */
    @Transactional(readOnly = true)
    public Optional<Species> getSpeciesById(Long speciesId) {
        return speciesRepository.findById(speciesId);
    }

    /**
     * Создать новое растение с расписанием полива.
     *
     * Если локация не выбрана явно, растение попадает в дефолтную локацию пользователя:
     * "Мои растения".
     *
     * @param user         юзер-владелец
     * @param speciesId    ID вида, может быть null для своего растения
     * @param name         имя растения
     * @param intervalDays интервал полива в днях
     * @param nextDueAt    когда следующий полив
     * @return сохранённое растение с расписанием
     */
    @Transactional
    public Plant createPlantWithWateringSchedule(
            User user,
            Long speciesId,
            String name,
            Integer intervalDays,
            LocalDateTime nextDueAt
    ) {
        Species species = speciesId != null
                ? speciesRepository.findById(speciesId).orElse(null)
                : null;

        Plant plant = Plant.builder()
                .user(user)
                .species(species)
                .name(name)
                .location(locationService.getOrCreateDefaultLocation(user))
                .build();

        plant = plantRepository.save(plant);

        log.info(
                "Created plant '{}' (id={}) for user {} in location {}",
                name,
                plant.getId(),
                user.getTelegramChatId(),
                plant.getLocation().getId()
        );

        CareSchedule wateringSchedule = CareSchedule.builder()
                .plant(plant)
                .taskType(TaskType.WATERING)
                .intervalDays(intervalDays)
                .nextDueAt(nextDueAt)
                .active(true)
                .build();

        careScheduleRepository.save(wateringSchedule);

        log.info(
                "Created watering schedule for plant {} (nextDueAt={})",
                plant.getId(),
                nextDueAt
        );

        plant.addSchedule(wateringSchedule);

        return plant;
    }

    /**
     * Создать новое растение с расписанием полива и конкретной локацией.
     *
     * Этот метод пригодится позже, когда в Telegram-flow добавишь выбор комнаты.
     */
    @Transactional
    public Plant createPlantWithWateringSchedule(
            User user,
            Long speciesId,
            String name,
            Integer intervalDays,
            LocalDateTime nextDueAt,
            Long locationId
    ) {
        Species species = speciesId != null
                ? speciesRepository.findById(speciesId).orElse(null)
                : null;

        Plant plant = Plant.builder()
                .user(user)
                .species(species)
                .name(name)
                .location(locationId != null
                        ? locationService.getLocation(user.getId(), locationId)
                        : locationService.getOrCreateDefaultLocation(user))
                .build();

        plant = plantRepository.save(plant);

        log.info(
                "Created plant '{}' (id={}) for user {} in location {}",
                name,
                plant.getId(),
                user.getTelegramChatId(),
                plant.getLocation().getId()
        );

        CareSchedule wateringSchedule = CareSchedule.builder()
                .plant(plant)
                .taskType(TaskType.WATERING)
                .intervalDays(intervalDays)
                .nextDueAt(nextDueAt)
                .active(true)
                .build();

        careScheduleRepository.save(wateringSchedule);

        log.info(
                "Created watering schedule for plant {} (nextDueAt={})",
                plant.getId(),
                nextDueAt
        );

        plant.addSchedule(wateringSchedule);

        return plant;
    }

    /**
     * Добавляет расписание ухода (MISTING или FERTILIZING) к существующему растению.
     * WATERING уже создаётся при создании растения.
     * Если расписание данного типа уже есть — активирует его с новым интервалом.
     *
     * @param plant       растение
     * @param taskType    тип задачи (MISTING или FERTILIZING)
     * @param intervalDays интервал в днях
     * @param nextDueAt   время следующего события
     * @return сохранённое расписание
     */
    @Transactional
    public CareSchedule addCareSchedule(
            Plant plant,
            TaskType taskType,
            Integer intervalDays,
            LocalDateTime nextDueAt
    ) {
        // Если расписание уже существует — обновляем, не создаём дубль
        return careScheduleRepository
                .findByPlantIdAndTaskType(plant.getId(), taskType)
                .map(existing -> {
                    existing.setIntervalDays(intervalDays);
                    existing.setNextDueAt(nextDueAt);
                    existing.setActive(true);
                    CareSchedule saved = careScheduleRepository.save(existing);
                    log.info("Updated {} schedule for plant {} (nextDueAt={})",
                            taskType, plant.getId(), nextDueAt);
                    return saved;
                })
                .orElseGet(() -> {
                    CareSchedule schedule = CareSchedule.builder()
                            .plant(plant)
                            .taskType(taskType)
                            .intervalDays(intervalDays)
                            .nextDueAt(nextDueAt)
                            .active(true)
                            .build();
                    CareSchedule saved = careScheduleRepository.save(schedule);
                    log.info("Created {} schedule for plant {} (nextDueAt={})",
                            taskType, plant.getId(), nextDueAt);
                    return saved;
                });
    }

    /**
     * Деактивирует расписание заданного типа для растения.
     * Используется при отключении MISTING/FERTILIZING тумблером в карточке.
     */
    @Transactional
    public void deactivateCareSchedule(Plant plant, TaskType taskType) {
        careScheduleRepository
                .findByPlantIdAndTaskType(plant.getId(), taskType)
                .ifPresent(schedule -> {
                    schedule.setActive(false);
                    careScheduleRepository.save(schedule);
                    log.info("Deactivated {} schedule for plant {}", taskType, plant.getId());
                });
    }

    /**
     * Получить все активные расписания растения.
     * Используется в карточке растения для отображения трёх таймеров.
     */
    @Transactional(readOnly = true)
    public List<CareSchedule> getActiveSchedules(Long plantId) {
        return careScheduleRepository.findAllByPlantId(plantId).stream()
                .filter(CareSchedule::isActive)
                .toList();
    }

    /**
     * Переместить растение в другую локацию.
     */
    @Transactional
    public Plant movePlantToLocation(Long userId, Long plantId, Long locationId) {
        return locationService.movePlant(userId, plantId, locationId);
    }

    /**
     * Сохранить telegram file_id фото растения.
     * Сами файлы на сервер не скачиваем — Telegram хранит фото у себя,
     * нам достаточно file_id для последующих sendPhoto.
     *
     * @param userId  владелец растения (защита от обращения к чужому растению)
     * @param plantId ID растения
     * @param fileId  file_id из Telegram API (берём самый большой PhotoSize)
     * @return обновлённое растение
     */
    @Transactional
    public Plant updatePhotoFileId(Long userId, Long plantId, String fileId) {
        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId)
                .orElseThrow(() -> new IllegalStateException(
                        "Plant " + plantId + " not found for user " + userId
                ));

        plant.setPhotoFileId(fileId);
        Plant saved = plantRepository.save(plant);

        log.info("Updated photo_file_id for plant {} (user {})", plantId, userId);

        return saved;
    }

    /**
     * Валидация имени растения.
     */
    public static boolean isValidPlantName(String name) {
        if (name == null) {
            return false;
        }

        String trimmed = name.trim();

        return !trimmed.isEmpty() && trimmed.length() <= 100;
    }

    /**
     * Валидация интервала полива.
     */
    public static boolean isValidInterval(int days) {
        return days >= 1 && days <= 365;
    }
}
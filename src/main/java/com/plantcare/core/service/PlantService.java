package com.plantcare.core.service;

import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.Season;
import com.plantcare.core.domain.enums.SeasonalOverride;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.SpeciesRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlantService {

    private final PlantRepository plantRepository;
    private final CareScheduleRepository careScheduleRepository;
    private final CareHistoryRepository careHistoryRepository;
    private final SpeciesRepository speciesRepository;
    private final LocationService locationService;
    private final com.plantcare.core.seasonal.service.SeasonalIntervalService seasonalIntervalService;
    private final HealthScoreService healthScoreService;
    private final PlantDiagnosisReportService plantDiagnosisReportService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Plant> listPlants(Long userId, Long locationId, int offset, int limit) {
        return queryPlants(userId, locationId, offset, limit);
    }

    private List<Plant> queryPlants(Long userId, Long locationId, int offset, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safeOffset = Math.max(0, offset);

        if (safeOffset % safeLimit != 0) {
            throw new IllegalArgumentException("offset must be a multiple of limit");
        }

        int page = safeOffset / safeLimit;
        Pageable pageable = PageRequest.of(page, safeLimit, Sort.by("name").ascending());

        if (locationId != null) {
            return plantRepository.findAllByUserIdAndLocationIdAndArchivedAtIsNullOrderByNameAsc(
                    userId,
                    locationId,
                    pageable
            );
        }

        return plantRepository.findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public HealthScoreService.HealthScore getPlantHealth(Long userId, Long plantId) {
        Plant plant = getPlantOrThrow(userId, plantId);
        return healthScoreService.computeForPlant(plant);
    }

    @Transactional(readOnly = true)
    public PlantDiagnosisReportService.DiagnosisReport getPlantDiagnosis(Long userId, Long plantId) {
        Plant plant = getPlantOrThrow(userId, plantId);
        return plantDiagnosisReportService.diagnose(plant);
    }

    @Transactional(readOnly = true)
    public Plant getPlantOrThrow(Long userId, Long plantId) {
        Plant plant = plantRepository.findByIdWithLocationAndSpecies(plantId)
                .orElseThrow(() -> new EntityNotFoundException("Plant not found: " + plantId));

        if (!plant.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("Access denied");
        }

        if (plant.isArchived()) {
            throw new EntityNotFoundException("Plant not found: " + plantId);
        }

        return plant;
    }

    @Transactional(readOnly = true)
    public long countPlants(Long userId, Long locationId) {
        if (locationId != null) {
            return plantRepository.countByUserIdAndLocationIdAndArchivedAtIsNull(userId, locationId);
        }

        return plantRepository.countByUserIdAndArchivedAtIsNull(userId);
    }

    public record PlantWithHealth(Plant plant, HealthScoreService.HealthScore health) {}

    @Transactional(readOnly = true)
    public PlantWithHealth getPlantWithHealth(Long userId, Long plantId) {
        Plant plant = getPlantOrThrow(userId, plantId);
        return new PlantWithHealth(plant, healthScoreService.computeForPlant(plant));
    }

    @Transactional(readOnly = true)
    public List<PlantWithHealth> listPlantsWithHealth(Long userId, Long locationId, int offset, int limit) {
        List<Plant> plants = queryPlants(userId, locationId, offset, limit);
        return plants.stream()
                .map(p -> new PlantWithHealth(p, healthScoreService.computeForPlant(p)))
                .toList();
    }

    public record PlantFamily(Plant parent, List<Plant> children) {
    }

    @Transactional
    public Plant createPlant(
            User user,
            String name,
            String notes,
            Long locationId,
            Long speciesId,
            Long parentPlantId
    ) {
        Location location;
        if (locationId != null) {
            location = locationService.getUserLocationOrThrow(user.getId(), locationId);
        } else {
            location = locationService.getOrCreateDefaultLocation(user);
        }

        Species species = null;
        if (speciesId != null) {
            species = speciesRepository.findById(speciesId)
                    .orElseThrow(() -> new EntityNotFoundException("Species not found: " + speciesId));
        }

        Plant parent = null;
        if (parentPlantId != null) {
            parent = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(user.getId(), parentPlantId)
                    .orElseThrow(() -> new EntityNotFoundException("Parent plant not found: " + parentPlantId));
        }

        Plant plant = Plant.builder()
                .user(user)
                .location(location)
                .name(name)
                .notes(notes)
                .species(species)
                .parent(parent)
                .build();

        Plant saved = plantRepository.save(plant);

        log.info(
                "Created plant '{}' (id={}, speciesId={}, parentPlantId={}) via REST API for user {}",
                name,
                saved.getId(),
                speciesId,
                parentPlantId,
                user.getId()
        );

        return saved;
    }
    @Transactional
    public Plant createPlant(
            User user,
            String name,
            String notes,
            Long locationId,
            Long speciesId
    ) {
        return createPlant(user, name, notes, locationId, speciesId, null);
    }

    public record SeasonalInfo(boolean active, int summerIntervalDays, int winterIntervalDays) {}

    /**
     * Создаёт растение и расписания ухода в одной транзакции.
     *
     * <p>Если {@code schedules} null или пуст — применяются дефолтные расписания вида
     * (как в {@link #createPlantWithDefaultSchedules}). Иначе создаются только
     * переданные типы с {@code enabled=true}; остальные типы получают дефолтный
     * интервал с {@code enabled=false}.
     *
     * @param schedules опциональные расписания при создании; null или пустой список = дефолты вида
     * @throws IllegalArgumentException если в {@code schedules} присутствует два элемента с одинаковым {@code type}
     */
    @Transactional
    public Plant createPlant(
            User user,
            String name,
            String notes,
            Long locationId,
            Long speciesId,
            Long parentPlantId,
            List<ScheduleInputDto> schedules
    ) {
        // internal call — already within @Transactional boundary, proxy bypass is intentional
        Plant plant = createPlant(user, name, notes, locationId, speciesId, parentPlantId);

        if (schedules == null || schedules.isEmpty()) {
            LocalDateTime now = LocalDateTime.now(clock);
            for (TaskType type : SCHEDULE_ORDER) {
                int interval = defaultIntervalFor(plant, type);
                boolean active = type == TaskType.WATERING;
                LocalDateTime nextDueAt = active
                        ? now.plusDays(seasonalIntervalService.effectiveIntervalDays(plant, user, interval))
                        : now.plusDays(interval);
                CareSchedule schedule = CareSchedule.builder()
                        .plant(plant)
                        .taskType(type)
                        .intervalDays(interval)
                        .nextDueAt(nextDueAt)
                        .active(active)
                        .build();
                careScheduleRepository.save(schedule);
            }
            log.info("Seeded default schedules for plant {} (user {})", plant.getId(), user.getId());
        } else {
            long distinctCount = schedules.stream()
                    .map(ScheduleInputDto::type)
                    .distinct()
                    .count();
            if (distinctCount != schedules.size()) {
                throw new IllegalArgumentException("Дублирующийся тип расписания в массиве schedules");
            }

            java.util.Map<TaskType, ScheduleInputDto> inputMap = schedules.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            ScheduleInputDto::type,
                            s -> s
                    ));

            LocalDateTime now = LocalDateTime.now(clock);
            for (TaskType type : SCHEDULE_ORDER) {
                ScheduleInputDto input = inputMap.get(type);
                if (input != null) {
                    int every = input.every();
                    if (!isValidInterval(every)) {
                        throw new IllegalArgumentException("Интервал должен быть от 1 до 365 дней: " + type);
                    }
                    Integer amountMl = type == TaskType.WATERING ? input.amountMl() : null;
                    int effectiveInterval = seasonalIntervalService.effectiveIntervalDays(plant, user, every);
                    CareSchedule schedule = CareSchedule.builder()
                            .plant(plant)
                            .taskType(type)
                            .intervalDays(every)
                            .amountMl(amountMl)
                            .nextDueAt(now.plusDays(effectiveInterval))
                            .active(true)
                            .build();
                    careScheduleRepository.save(schedule);
                } else {
                    int interval = defaultIntervalFor(plant, type);
                    CareSchedule schedule = CareSchedule.builder()
                            .plant(plant)
                            .taskType(type)
                            .intervalDays(interval)
                            .nextDueAt(now.plusDays(interval))
                            .active(false)
                            .build();
                    careScheduleRepository.save(schedule);
                }
            }
            log.info(
                    "Created {} custom schedule(s) for plant {} (user {})",
                    schedules.size(),
                    plant.getId(),
                    user.getId()
            );
        }

        return plant;
    }

    public record ScheduleInputDto(
            TaskType type,
            int every,
            Integer amountMl
    ) {
    }

    public record ScheduleView(
            TaskType type,
            int every,
            Integer amountMl,
            boolean enabled,
            LocalDateTime nextDueAt,
            SeasonalInfo seasonal
    ) {
    }

    private static final List<TaskType> SCHEDULE_ORDER = List.of(
            TaskType.WATERING,
            TaskType.MISTING,
            TaskType.FERTILIZING,
            TaskType.SOIL_CHECK
    );

    @Transactional(readOnly = true)
    public List<ScheduleView> getSchedules(Long userId, Long plantId) {
        Plant plant = getPlantOrThrow(userId, plantId);
        User user = plant.getUser();

        java.util.Map<TaskType, CareSchedule> existing = careScheduleRepository
                .findAllByPlantId(plant.getId())
                .stream()
                .collect(java.util.stream.Collectors.toMap(CareSchedule::getTaskType, schedule -> schedule));

        return SCHEDULE_ORDER.stream()
                .map(type -> {
                    CareSchedule row = existing.get(type);

                    if (row != null) {
                        boolean enabled = row.isActive();
                        int base = row.getIntervalDays();
                        SeasonalInfo seasonal = buildSeasonalInfo(plant, user, base);

                        return new ScheduleView(
                                type,
                                base,
                                row.getAmountMl(),
                                enabled,
                                enabled ? row.getNextDueAt() : null,
                                seasonal
                        );
                    }

                    int base = defaultIntervalFor(plant, type);
                    SeasonalInfo seasonal = buildSeasonalInfo(plant, user, base);

                    return new ScheduleView(
                            type,
                            base,
                            null,
                            false,
                            null,
                            seasonal
                    );
                })
                .toList();
    }

    private SeasonalInfo buildSeasonalInfo(Plant plant, User user, int baseIntervalDays) {
        return new SeasonalInfo(
                seasonalIntervalService.isSeasonalActive(plant, user),
                seasonalIntervalService.effectiveIntervalForSeason(plant, user, baseIntervalDays, Season.SUMMER),
                seasonalIntervalService.effectiveIntervalForSeason(plant, user, baseIntervalDays, Season.WINTER)
        );
    }

    @Transactional
    public ScheduleView updateSchedule(
            Long userId,
            Long plantId,
            TaskType type,
            int every,
            Integer amountMl,
            boolean enabled,
            SeasonalOverride seasonalOverride
    ) {
        if (!isValidInterval(every)) {
            throw new IllegalArgumentException("Интервал должен быть от 1 до 365 дней");
        }

        Integer effectiveAmount = type == TaskType.WATERING ? amountMl : null;
        if (effectiveAmount != null && effectiveAmount <= 0) {
            throw new IllegalArgumentException("Объём должен быть положительным");
        }

        Plant plant = getPlantOrThrow(userId, plantId);

        if (seasonalOverride != null) {
            plant.setSeasonalOverride(seasonalOverride);
        }

        int effectiveInterval = seasonalIntervalService.effectiveIntervalDays(
                plant,
                plant.getUser(),
                every
        );

        LocalDateTime now = LocalDateTime.now(clock);

        CareSchedule schedule = careScheduleRepository
                .findByPlantIdAndTaskType(plant.getId(), type)
                .orElse(null);

        if (schedule != null) {
            schedule.setIntervalDays(every);
            schedule.setAmountMl(effectiveAmount);
            schedule.setActive(enabled);

            if (enabled) {
                schedule.rescheduleFrom(now, effectiveInterval);
            }
        } else {
            schedule = CareSchedule.builder()
                    .plant(plant)
                    .taskType(type)
                    .intervalDays(every)
                    .amountMl(effectiveAmount)
                    .nextDueAt(now.plusDays(effectiveInterval))
                    .active(enabled)
                    .build();
        }

        CareSchedule saved = careScheduleRepository.save(schedule);

        log.info(
                "Updated {} schedule for plant {} (every={}, amountMl={}, enabled={}, seasonalOverride={}, user {})",
                type,
                plantId,
                every,
                effectiveAmount,
                enabled,
                seasonalOverride,
                userId
        );

        User user = plant.getUser();
        SeasonalInfo seasonal = buildSeasonalInfo(plant, user, every);

        return new ScheduleView(
                type,
                every,
                effectiveAmount,
                enabled,
                enabled ? saved.getNextDueAt() : null,
                seasonal
        );
    }

    @Transactional
    public Plant createPlantWithDefaultSchedules(
            User user,
            String name,
            String notes,
            Long locationId,
            Long speciesId
    ) {
        Plant plant = createPlant(user, name, notes, locationId, speciesId, null);

        LocalDateTime now = LocalDateTime.now(clock);

        for (TaskType type : SCHEDULE_ORDER) {
            int interval = defaultIntervalFor(plant, type);
            boolean active = type == TaskType.WATERING;

            LocalDateTime nextDueAt = active
                    ? now.plusDays(seasonalIntervalService.effectiveIntervalDays(plant, user, interval))
                    : now.plusDays(interval);

            CareSchedule schedule = CareSchedule.builder()
                    .plant(plant)
                    .taskType(type)
                    .intervalDays(interval)
                    .nextDueAt(nextDueAt)
                    .active(active)
                    .build();

            careScheduleRepository.save(schedule);
        }

        log.info("Seeded default schedules for plant {} (user {})", plant.getId(), user.getId());

        return plant;
    }

    @Transactional(readOnly = true)
    public PlantFamily getPlantFamily(Long userId, Long plantId) {
        Plant plant = getPlantOrThrow(userId, plantId);

        Plant parent = null;
        if (plant.getParent() != null) {
            Long parentId = plant.getParent().getId();

            parent = plantRepository
                    .findByUserIdAndIdAndArchivedAtIsNull(userId, parentId)
                    .orElse(null);
        }

        List<Plant> children = plantRepository
                .findAllByUserIdAndParentIdAndArchivedAtIsNullOrderByNameAsc(
                        userId,
                        plant.getId()
                );

        return new PlantFamily(parent, children);
    }

    @Transactional
    public Plant updatePlant(Long userId, Long plantId, String name, String notes, Long locationId) {
        Plant plant = getPlantOrThrow(userId, plantId);

        if (name != null) {
            plant.setName(name);
        }

        if (notes != null) {
            plant.setNotes(notes);
        }

        if (locationId != null) {
            Location location = locationService.getUserLocationOrThrow(userId, locationId);
            plant.setLocation(location);
        }

        Plant saved = plantRepository.save(plant);

        log.info("Updated plant {} via REST API (user {})", plantId, userId);

        return saved;
    }

    @Transactional(readOnly = true)
    public List<Species> getPopularSpecies(int limit) {
        return speciesRepository.findAllByOrderByPopularityDesc(Limit.of(limit));
    }

    @Transactional(readOnly = true)
    public List<Species> searchSpecies(String query, int limit) {
        return speciesRepository.searchByQuery(query, limit);
    }

    @Transactional(readOnly = true)
    public Optional<Species> getSpeciesById(Long speciesId) {
        return speciesRepository.findById(speciesId);
    }

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

    @Transactional
    public Plant createPlantWithWateringSchedule(
            User user,
            Long speciesId,
            String name,
            Integer intervalDays,
            LocalDateTime nextDueAt,
            Long locationId
    ) {
        return createPlantWithWateringSchedule(
                user,
                speciesId,
                name,
                intervalDays,
                nextDueAt,
                locationId,
                null
        );
    }

    @Transactional
    public Plant createPlantWithWateringSchedule(
            User user,
            Long speciesId,
            String name,
            Integer intervalDays,
            LocalDateTime nextDueAt,
            Long locationId,
            LocalDate acquiredAt
    ) {
        Species species = speciesId != null
                ? speciesRepository.findById(speciesId).orElse(null)
                : null;

        Plant plant = Plant.builder()
                .user(user)
                .species(species)
                .name(name)
                .acquiredAt(acquiredAt)
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

    @Transactional
    public CareSchedule addCareSchedule(
            Plant plant,
            TaskType taskType,
            Integer intervalDays,
            LocalDateTime nextDueAt
    ) {
        return careScheduleRepository
                .findByPlantIdAndTaskType(plant.getId(), taskType)
                .map(existing -> {
                    existing.setIntervalDays(intervalDays);
                    existing.setNextDueAt(nextDueAt);
                    existing.setActive(true);

                    CareSchedule saved = careScheduleRepository.save(existing);

                    log.info(
                            "Updated {} schedule for plant {} (nextDueAt={})",
                            taskType,
                            plant.getId(),
                            nextDueAt
                    );

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

                    log.info(
                            "Created {} schedule for plant {} (nextDueAt={})",
                            taskType,
                            plant.getId(),
                            nextDueAt
                    );

                    return saved;
                });
    }

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

    @Transactional(readOnly = true)
    public List<CareSchedule> getActiveSchedules(Long plantId) {
        return careScheduleRepository.findAllByPlantId(plantId)
                .stream()
                .filter(CareSchedule::isActive)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CareSchedule> getAllSchedules(Long plantId) {
        return careScheduleRepository.findAllByPlantId(plantId);
    }

    @Transactional(readOnly = true)
    public Optional<Plant> getPlantForUser(Long userId, Long plantId) {
        Optional<Plant> opt = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId);

        opt.ifPresent(plant -> {
            if (plant.getLocation() != null) {
                plant.getLocation().getName();
                plant.getLocation().getEmoji();
            }
        });

        return opt;
    }

    @Transactional
    public Plant movePlantToLocation(Long userId, Long plantId, Long locationId) {
        return locationService.movePlant(userId, plantId, locationId);
    }

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

    public static final int NOTE_MAX_LENGTH = 2000;

    @Transactional
    public Plant renamePlant(Long userId, Long plantId, String newName) {
        if (!isValidPlantName(newName)) {
            throw new IllegalArgumentException("Имя должно быть от 1 до 100 символов и не пустым");
        }

        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId)
                .orElseThrow(() -> new IllegalArgumentException("Растение не найдено"));

        plant.setName(newName.trim());

        Plant saved = plantRepository.save(plant);

        log.info("Renamed plant {} to '{}' (user {})", plantId, newName, userId);

        return saved;
    }

    @Transactional
    public Plant updateNotes(Long userId, Long plantId, String notes) {
        String normalized = notes == null ? null : notes.trim();

        if (normalized != null && normalized.isEmpty()) {
            normalized = null;
        }

        if (normalized != null && normalized.length() > NOTE_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Заметка не может быть длиннее " + NOTE_MAX_LENGTH + " символов"
            );
        }

        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId)
                .orElseThrow(() -> new IllegalArgumentException("Растение не найдено"));

        plant.setNotes(normalized);

        Plant saved = plantRepository.save(plant);

        log.info(
                "Updated notes for plant {} (user {}, cleared={})",
                plantId,
                userId,
                normalized == null
        );

        return saved;
    }

    @Transactional
    public void archivePlant(Long userId, Long plantId) {
        Plant plant = getPlantOrThrow(userId, plantId);

        plant.archive();
        plantRepository.save(plant);

        log.info("Archived plant {} (user {})", plantId, userId);
    }

    @Transactional
    public CareSchedule updateScheduleInterval(
            Long userId,
            Long plantId,
            TaskType taskType,
            int intervalDays
    ) {
        if (!isValidInterval(intervalDays)) {
            throw new IllegalArgumentException("Интервал должен быть от 1 до 365 дней");
        }

        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId)
                .orElseThrow(() -> new IllegalArgumentException("Растение не найдено"));

        CareSchedule schedule = careScheduleRepository
                .findByPlantIdAndTaskType(plant.getId(), taskType)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Расписание " + taskType + " не настроено"
                ));

        schedule.setIntervalDays(intervalDays);

        CareSchedule saved = careScheduleRepository.save(schedule);

        log.info(
                "Updated {} interval to {} for plant {} (user {})",
                taskType,
                intervalDays,
                plantId,
                userId
        );

        return saved;
    }

    @Transactional
    public CareSchedule rescheduleSchedule(
            Long userId,
            Long plantId,
            TaskType taskType,
            LocalDateTime nextDueAt
    ) {
        if (nextDueAt == null) {
            throw new IllegalArgumentException("nextDueAt не задан");
        }

        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId)
                .orElseThrow(() -> new IllegalArgumentException("Растение не найдено"));

        CareSchedule schedule = careScheduleRepository
                .findByPlantIdAndTaskType(plant.getId(), taskType)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Расписание " + taskType + " не настроено"
                ));

        schedule.setNextDueAt(nextDueAt);

        CareSchedule saved = careScheduleRepository.save(schedule);

        log.info(
                "Rescheduled {} for plant {} to {} (user {})",
                taskType,
                plantId,
                nextDueAt,
                userId
        );

        return saved;
    }

    @Transactional
    public CareSchedule toggleSchedule(Long userId, Long plantId, TaskType taskType) {
        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId)
                .orElseThrow(() -> new IllegalArgumentException("Растение не найдено"));

        CareSchedule existing = careScheduleRepository
                .findByPlantIdAndTaskType(plant.getId(), taskType)
                .orElse(null);

        if (existing != null) {
            existing.setActive(!existing.isActive());

            if (existing.isActive()
                    && existing.getNextDueAt() != null
                    && existing.getNextDueAt().isBefore(LocalDateTime.now(clock))) {
                int effective = seasonalIntervalService.effectiveIntervalDays(
                        plant,
                        plant.getUser(),
                        existing.getIntervalDays()
                );

                existing.rescheduleFrom(LocalDateTime.now(clock), effective);
            }

            CareSchedule saved = careScheduleRepository.save(existing);

            log.info(
                    "Toggled {} for plant {} → active={} (user {})",
                    taskType,
                    plantId,
                    saved.isActive(),
                    userId
            );

            return saved;
        }

        int defaultInterval = defaultIntervalFor(plant, taskType);
        int effectiveInterval = seasonalIntervalService.effectiveIntervalDays(
                plant,
                plant.getUser(),
                defaultInterval
        );

        LocalDateTime nextDueAt = LocalDateTime.now(clock).plusDays(effectiveInterval);

        CareSchedule fresh = CareSchedule.builder()
                .plant(plant)
                .taskType(taskType)
                .intervalDays(defaultInterval)
                .nextDueAt(nextDueAt)
                .active(true)
                .build();

        CareSchedule saved = careScheduleRepository.save(fresh);

        log.info(
                "Created {} schedule for plant {} (interval={}, nextDueAt={}, user {})",
                taskType,
                plantId,
                defaultInterval,
                nextDueAt,
                userId
        );

        return saved;
    }

    private int defaultIntervalFor(Plant plant, TaskType taskType) {
        Integer fromSpecies = null;
        Species species = plant.getSpecies();

        if (species != null) {
            fromSpecies = switch (taskType) {
                case WATERING -> species.getWateringDays();
                case MISTING -> species.getMistingDays();
                case FERTILIZING -> species.getFertilizingDays();
                case SOIL_CHECK -> species.getSoilCheckDays();
            };
        }

        if (fromSpecies != null && isValidInterval(fromSpecies)) {
            return fromSpecies;
        }

        return switch (taskType) {
            case WATERING -> 7;
            case MISTING -> 3;
            case FERTILIZING -> 14;
            case SOIL_CHECK -> 3;
        };
    }

    public static boolean isValidPlantName(String name) {
        if (name == null) {
            return false;
        }

        String trimmed = name.trim();

        return !trimmed.isEmpty() && trimmed.length() <= 100;
    }

    public static boolean isValidInterval(int days) {
        return days >= 1 && days <= 365;
    }

    private static final int CARE_DEDUP_SECONDS = 60;
    private static final int CARE_GRACE_PERIOD_HOURS = 24;

    public record MarkCareDoneResult(
            boolean wasDuplicate,
            CareSchedule schedule,
            CareHistory history,
            LocalDateTime doneAt
    ) {
    }

    @Transactional
    public MarkCareDoneResult markCareDone(Long userId, Long plantId, TaskType taskType) {
        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Растение не найдено: id=" + plantId
                ));

        CareSchedule schedule = careScheduleRepository
                .findByPlantIdAndTaskType(plant.getId(), taskType)
                .filter(CareSchedule::isActive)
                .orElse(null);

        if (schedule == null) {
            log.warn("Active schedule {} not found for plant {}", taskType, plant.getId());
            return null;
        }

        LocalDateTime now = LocalDateTime.now(clock);

        Optional<CareHistory> lastEntry = careHistoryRepository
                .findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(plant.getId(), taskType);

        if (lastEntry.isPresent()
                && lastEntry.get().getDoneAt().plusSeconds(CARE_DEDUP_SECONDS).isAfter(now)) {
            log.debug("Duplicate mark-done for plant {} {}, ignoring", plant.getId(), taskType);
            return new MarkCareDoneResult(true, schedule, lastEntry.get(), now);
        }

        boolean wasOnTime = !now.isAfter(
                schedule.getNextDueAt().plusHours(CARE_GRACE_PERIOD_HOURS)
        );

        CareHistory history = CareHistory.builder()
                .plant(plant)
                .taskType(taskType)
                .doneAt(now)
                .onTime(wasOnTime)
                .build();

        history = careHistoryRepository.save(history);

        schedule.rescheduleFrom(now);
        schedule = careScheduleRepository.save(schedule);

        log.info(
                "Plant {} {} marked as done from card (on_time={}, next due at {})",
                plant.getId(),
                taskType,
                wasOnTime,
                schedule.getNextDueAt()
        );

        return new MarkCareDoneResult(false, schedule, history, now);
    }

    public record BulkCareDoneResult(int updated, int deduped, String locationName) {
        public int total() {
            return updated + deduped;
        }
    }

    @Transactional
    public BulkCareDoneResult markBulkCareDone(Long userId, Long locationId, TaskType taskType) {
        List<CareSchedule> schedules = careScheduleRepository
                .findActiveSchedulesInUserLocation(userId, locationId, taskType);

        if (schedules.isEmpty()) {
            return new BulkCareDoneResult(0, 0, null);
        }

        String locationName = schedules.get(0).getPlant().getLocation().getDisplayName();

        LocalDateTime now = LocalDateTime.now(clock);
        int updated = 0;
        int deduped = 0;

        for (CareSchedule schedule : schedules) {
            Plant plant = schedule.getPlant();

            Optional<CareHistory> last = careHistoryRepository
                    .findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(plant.getId(), taskType);

            if (last.isPresent()
                    && last.get().getDoneAt().plusSeconds(CARE_DEDUP_SECONDS).isAfter(now)) {
                deduped++;
                continue;
            }

            boolean wasOnTime = schedule.getNextDueAt() == null
                    || !now.isAfter(schedule.getNextDueAt().plusHours(CARE_GRACE_PERIOD_HOURS));

            CareHistory history = CareHistory.builder()
                    .plant(plant)
                    .taskType(taskType)
                    .doneAt(now)
                    .onTime(wasOnTime)
                    .build();

            careHistoryRepository.save(history);

            schedule.rescheduleFrom(now);
            careScheduleRepository.save(schedule);

            updated++;
        }

        log.info(
                "Bulk care done: user={}, location={}, taskType={}, updated={}, deduped={}",
                userId,
                locationId,
                taskType,
                updated,
                deduped
        );

        return new BulkCareDoneResult(updated, deduped, locationName);
    }
}
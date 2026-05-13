package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareHistory;
import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.Species;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareHistoryRepository;
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
    private final CareHistoryRepository careHistoryRepository;
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
     * Получить все расписания растения (включая неактивные).
     * Используется на экране управления типами ухода — пользователь видит,
     * какие расписания вообще существуют, чтобы их включить/выключить.
     */
    @Transactional(readOnly = true)
    public List<CareSchedule> getAllSchedules(Long plantId) {
        return careScheduleRepository.findAllByPlantId(plantId);
    }

    /**
     * Получить растение пользователя (не архивное) для нужд UI-сервисов:
     * например, чтобы показать текущую заметку в промпте редактирования.
     * Также инициализирует lazy-ассоциации, которые могут понадобиться вне транзакции.
     */
    @Transactional(readOnly = true)
    public Optional<Plant> getPlantForUser(Long userId, Long plantId) {
        Optional<Plant> opt = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId);
        opt.ifPresent(plant -> {
            // Touch lazy fields to avoid LazyInitException downstream.
            if (plant.getLocation() != null) {
                plant.getLocation().getName();
                plant.getLocation().getEmoji();
            }
        });
        return opt;
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

    // =================================================================
    // Edit mode (issue #27): rename / note / archive / schedule edits
    // =================================================================

    /**
     * Лимит длины заметки. Колонка plants.notes — TEXT, ограничение чисто UX-овое.
     */
    public static final int NOTE_MAX_LENGTH = 500;

    /**
     * Переименование растения. Имя валидируется тем же правилом, что и при создании.
     */
    @Transactional
    public Plant renamePlant(Long userId, Long plantId, String newName) {
        if (!isValidPlantName(newName)) {
            throw new IllegalArgumentException(
                    "Имя должно быть от 1 до 100 символов и не пустым");
        }

        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId)
                .orElseThrow(() -> new IllegalArgumentException("Растение не найдено"));

        plant.setName(newName.trim());
        Plant saved = plantRepository.save(plant);

        log.info("Renamed plant {} to '{}' (user {})", plantId, newName, userId);
        return saved;
    }

    /**
     * Обновить заметку. null/blank → очистить.
     */
    @Transactional
    public Plant updateNotes(Long userId, Long plantId, String notes) {
        String normalized = notes == null ? null : notes.trim();
        if (normalized != null && normalized.isEmpty()) {
            normalized = null;
        }
        if (normalized != null && normalized.length() > NOTE_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Заметка не может быть длиннее " + NOTE_MAX_LENGTH + " символов");
        }

        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId)
                .orElseThrow(() -> new IllegalArgumentException("Растение не найдено"));

        plant.setNotes(normalized);
        Plant saved = plantRepository.save(plant);

        log.info("Updated notes for plant {} (user {}, cleared={})",
                plantId, userId, normalized == null);
        return saved;
    }

    /**
     * Архивирует растение (soft-delete). История ухода остаётся для статистики.
     */
    @Transactional
    public void archivePlant(Long userId, Long plantId) {
        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId)
                .orElseThrow(() -> new IllegalArgumentException("Растение не найдено"));

        plant.archive();
        plantRepository.save(plant);

        log.info("Archived plant {} (user {})", plantId, userId);
    }

    /**
     * Изменить интервал расписания. Пересчёта nextDueAt НЕ делаем —
     * пользователь обычно хочет отдельно перенести ближайшее напоминание.
     */
    @Transactional
    public CareSchedule updateScheduleInterval(
            Long userId, Long plantId, TaskType taskType, int intervalDays
    ) {
        if (!isValidInterval(intervalDays)) {
            throw new IllegalArgumentException(
                    "Интервал должен быть от 1 до 365 дней");
        }

        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId)
                .orElseThrow(() -> new IllegalArgumentException("Растение не найдено"));

        CareSchedule schedule = careScheduleRepository
                .findByPlantIdAndTaskType(plant.getId(), taskType)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Расписание " + taskType + " не настроено"));

        schedule.setIntervalDays(intervalDays);
        CareSchedule saved = careScheduleRepository.save(schedule);

        log.info("Updated {} interval to {} for plant {} (user {})",
                taskType, intervalDays, plantId, userId);
        return saved;
    }

    /**
     * Перенести ближайшее срабатывание расписания на новое время.
     */
    @Transactional
    public CareSchedule rescheduleSchedule(
            Long userId, Long plantId, TaskType taskType, LocalDateTime nextDueAt
    ) {
        if (nextDueAt == null) {
            throw new IllegalArgumentException("nextDueAt не задан");
        }

        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId)
                .orElseThrow(() -> new IllegalArgumentException("Растение не найдено"));

        CareSchedule schedule = careScheduleRepository
                .findByPlantIdAndTaskType(plant.getId(), taskType)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Расписание " + taskType + " не настроено"));

        schedule.setNextDueAt(nextDueAt);
        CareSchedule saved = careScheduleRepository.save(schedule);

        log.info("Rescheduled {} for plant {} to {} (user {})",
                taskType, plantId, nextDueAt, userId);
        return saved;
    }

    /**
     * Переключить активность расписания типа ухода:
     *   - если расписание есть и активно — выключаем (active=false);
     *   - если расписание есть и отключено — включаем (active=true);
     *   - если расписания нет — создаём новое с дефолтным интервалом и nextDueAt = today + interval.
     *
     * Дефолтные интервалы берём из вида (Species). Если для данного вида не задан или null —
     * используем разумные хардкоды: WATERING=7, MISTING=3, FERTILIZING=14.
     */
    @Transactional
    public CareSchedule toggleSchedule(Long userId, Long plantId, TaskType taskType) {
        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId)
                .orElseThrow(() -> new IllegalArgumentException("Растение не найдено"));

        CareSchedule existing = careScheduleRepository
                .findByPlantIdAndTaskType(plant.getId(), taskType)
                .orElse(null);

        if (existing != null) {
            existing.setActive(!existing.isActive());
            // Если включили заново и nextDueAt в прошлом — сдвигаем на сегодня + интервал,
            // чтобы у пользователя не висело "просрочено на 100 дней".
            if (existing.isActive() && existing.getNextDueAt() != null
                    && existing.getNextDueAt().isBefore(LocalDateTime.now())) {
                existing.rescheduleFrom(LocalDateTime.now());
            }
            CareSchedule saved = careScheduleRepository.save(existing);
            log.info("Toggled {} for plant {} → active={} (user {})",
                    taskType, plantId, saved.isActive(), userId);
            return saved;
        }

        int defaultInterval = defaultIntervalFor(plant, taskType);
        LocalDateTime nextDueAt = LocalDateTime.now().plusDays(defaultInterval);

        CareSchedule fresh = CareSchedule.builder()
                .plant(plant)
                .taskType(taskType)
                .intervalDays(defaultInterval)
                .nextDueAt(nextDueAt)
                .active(true)
                .build();
        CareSchedule saved = careScheduleRepository.save(fresh);

        log.info("Created {} schedule for plant {} (interval={}, nextDueAt={}, user {})",
                taskType, plantId, defaultInterval, nextDueAt, userId);
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
            };
        }
        if (fromSpecies != null && isValidInterval(fromSpecies)) {
            return fromSpecies;
        }
        return switch (taskType) {
            case WATERING -> 7;
            case MISTING -> 3;
            case FERTILIZING -> 14;
        };
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

    /**
     * Антидубль для отметки "сделано": если в течение DEDUP_SECONDS уже была запись
     * такого же типа — игнорируем повторное нажатие. Совпадает с правилом в
     * {@link NotificationCallbackService}, чтобы дребезг кнопок из карточки
     * и из уведомления вёл себя одинаково.
     */
    private static final int CARE_DEDUP_SECONDS = 60;

    /**
     * Льготный период "вовремя": done считается on_time, если выполнено
     * не позже nextDueAt + GRACE_PERIOD_HOURS.
     */
    private static final int CARE_GRACE_PERIOD_HOURS = 24;

    /**
     * Результат отметки выполнения. Возвращаем простой объект, чтобы вызывающий
     * код мог решить, что показать пользователю (новая дата, "уже отмечено", и т.п.).
     */
    public record MarkCareDoneResult(
            boolean wasDuplicate,
            CareSchedule schedule,
            CareHistory history,
            LocalDateTime doneAt
    ) {}

    /**
     * Отметить уход выполненным из карточки растения.
     *
     * Дублирует поведение {@link NotificationCallbackService} ("done"-действие)
     * для случая, когда юзер сам открыл карточку и нажал кнопку быстрого ухода:
     *   1. Если активного расписания этого типа у растения нет — возвращаем null.
     *   2. Если за последние {@value #CARE_DEDUP_SECONDS} сек уже было "сделано" —
     *      возвращаем wasDuplicate=true, в БД ничего не пишем.
     *   3. Иначе пишем запись в care_history (с флагом on_time) и сдвигаем
     *      next_due_at на интервал от фактического времени выполнения.
     *
     * @param userId   владелец растения (защита от чужого растения)
     * @param plantId  ID растения
     * @param taskType тип задачи (WATERING/MISTING/FERTILIZING)
     * @return результат отметки или null, если расписание не найдено
     */
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

        LocalDateTime now = LocalDateTime.now();

        // Дедуп — чтобы двойной тап по кнопке не создавал две записи
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

        log.info("Plant {} {} marked as done from card (on_time={}, next due at {})",
                plant.getId(), taskType, wasOnTime, schedule.getNextDueAt());

        return new MarkCareDoneResult(false, schedule, history, now);
    }
}
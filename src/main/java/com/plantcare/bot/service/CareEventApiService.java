package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareHistory;
import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareHistoryRepository;
import com.plantcare.bot.repository.CareScheduleRepository;
import com.plantcare.bot.repository.PlantRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Сервис для работы с событиями ухода через публичный REST API (issue #86).
 *
 * <p>Отделён от {@link PlantService} намеренно: он содержит логику,
 * специфичную для REST-контракта — идемпотентность по {@code clientId}
 * и compensation pattern для отмены записей.
 *
 * <p>Вызовы внешних API (Telegram) внутри транзакций запрещены.
 * Весь IO — только к БД.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CareEventApiService {

    /** Льготный период «вовремя»: выполнено не позже nextDueAt + 24 ч. */
    private static final int GRACE_PERIOD_HOURS = 24;

    private final PlantRepository plantRepository;
    private final CareHistoryRepository careHistoryRepository;
    private final CareScheduleRepository careScheduleRepository;
    private final Clock clock;

    /**
     * Регистрирует событие ухода.
     *
     * <p>Если передан {@code clientId} и запись с таким id уже существует —
     * возвращает её без повторной записи (идемпотентность).
     *
     * @param userId      внутренний id пользователя (из БД)
     * @param plantId     id растения
     * @param taskType    тип задачи (WATERING / MISTING / FERTILIZING)
     * @param performedAt момент выполнения (UTC)
     * @param note        необязательная заметка
     * @param clientId    UUID от клиента для идемпотентности; null — без проверки
     * @return сохранённая запись истории
     * @throws EntityNotFoundException если растение или активное расписание не найдены
     */
    public CareHistory registerEvent(
            Long userId,
            Long plantId,
            TaskType taskType,
            Instant performedAt,
            String note,
            String clientId
    ) {
        // 1. Идемпотентность: если clientId уже видели — возвращаем существующую запись.
        if (clientId != null) {
            var existing = careHistoryRepository.findByClientId(clientId);
            if (existing.isPresent()) {
                log.info("Idempotent repeat for clientId={}, returning existing history id={}",
                        clientId, existing.get().getId());
                return existing.get();
            }
        }

        // 2. Проверка ownership.
        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Plant not found: id=" + plantId + " for userId=" + userId));

        // 3. Активное расписание для данного типа задачи.
        CareSchedule schedule = careScheduleRepository
                .findByPlantIdAndTaskType(plant.getId(), taskType)
                .filter(CareSchedule::isActive)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No active schedule for taskType=" + taskType
                                + " on plant=" + plantId));

        // 4. Конвертация Instant → LocalDateTime (UTC).
        LocalDateTime performedAtLocal = LocalDateTime.ofInstant(performedAt, ZoneOffset.UTC);

        // 5. Расчёт onTime: льготный период 24 часа от nextDueAt.
        boolean onTime = !performedAtLocal.isAfter(
                schedule.getNextDueAt().plusHours(GRACE_PERIOD_HOURS));

        // 6. Создать и сохранить запись истории.
        CareHistory history = new CareHistory();
        history.setPlant(plant);
        history.setTaskType(taskType);
        history.setDoneAt(performedAtLocal);
        history.setOnTime(onTime);
        history.setNote(note);
        history.setClientId(clientId);
        try {
            history = careHistoryRepository.save(history);
        } catch (DataIntegrityViolationException e) {
            // Параллельный retry с тем же clientId уже вставил запись — возвращаем её.
            if (clientId != null) {
                return careHistoryRepository.findByClientId(clientId)
                        .orElseThrow(() -> e);
            }
            throw e;
        }

        // 7. Сдвинуть расписание.
        schedule.rescheduleFrom(performedAtLocal);
        careScheduleRepository.save(schedule);

        log.info("Registered care event: userId={}, plantId={}, taskType={}, onTime={}, clientId={}",
                userId, plantId, taskType, onTime, clientId);

        return history;
    }

    /**
     * Отменяет запись истории через compensation pattern.
     *
     * <p>Исходная запись не удаляется — создаётся компенсирующая,
     * на которую ставится FK {@code cancelled_by}.
     *
     * @param userId        внутренний id пользователя
     * @param careHistoryId id записи, которую нужно отменить
     * @throws EntityNotFoundException если запись не найдена
     * @throws AccessDeniedException   если запись принадлежит другому пользователю
     * @throws IllegalStateException   если запись уже отменена (→ 409 Conflict)
     */
    public void cancelEvent(Long userId, Long careHistoryId) {
        // 1. Найти исходную запись.
        CareHistory original = careHistoryRepository.findById(careHistoryId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "CareHistory not found: id=" + careHistoryId));

        // 2. Ownership.
        if (!original.getPlant().getUser().getId().equals(userId)) {
            throw new AccessDeniedException(
                    "CareHistory id=" + careHistoryId + " belongs to another user");
        }

        // 3. Уже отменена → 409 Conflict.
        if (original.isCancelled()) {
            throw new IllegalStateException(
                    "CareHistory id=" + careHistoryId + " is already cancelled");
        }

        // 4. Создать компенсирующую запись.
        CareHistory compensation = new CareHistory();
        compensation.setPlant(original.getPlant());
        compensation.setTaskType(original.getTaskType());
        compensation.setDoneAt(LocalDateTime.now(clock));
        compensation.setOnTime(false);
        compensation.setNote("Отменено через API");
        compensation = careHistoryRepository.save(compensation);

        // 5. Проставить FK на компенсирующую запись.
        original.setCancelledBy(compensation);
        careHistoryRepository.save(original);

        log.info("Cancelled care event: userId={}, careHistoryId={}, compensationId={}",
                userId, careHistoryId, compensation.getId());
    }
}

package com.plantcare.bot.service;

import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareHistoryRepository;
import com.plantcare.bot.repository.PlantRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Бизнес-логика «Memorial» / архива растений (issue #117).
 *
 * <p>Архив — это {@code plants.archived_at IS NOT NULL}. Все операции
 * проверяют принадлежность растения юзеру, чтобы из callback'а нельзя
 * было дотянуться до чужой записи.
 *
 * <p>«Удалить навсегда» делает реальный {@code DELETE FROM plants} — связанные
 * строки удаляются каскадом через FK {@code ON DELETE CASCADE} на уровне БД
 * (care_schedules, care_history, notifications_log, plant_anniversaries_sent
 * и т.д.). Поэтому на entity-уровне cascade не нужен.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlantArchiveService {

    private final PlantRepository plantRepository;
    private final CareHistoryRepository careHistoryRepository;

    /** Все архивные растения юзера, новые сверху. */
    @Transactional(readOnly = true)
    public List<Plant> listArchived(Long userId) {
        return plantRepository.findAllByUserIdAndArchivedAtIsNotNullOrderByArchivedAtDesc(userId);
    }

    /** Архивное растение юзера или {@link EntityNotFoundException}. */
    @Transactional(readOnly = true)
    public Plant getArchivedOrThrow(Long userId, Long plantId) {
        return plantRepository.findByUserIdAndIdAndArchivedAtIsNotNull(userId, plantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Archived plant not found: " + plantId));
    }

    /**
     * Восстановление из архива — просто сбрасываем {@code archived_at}.
     * Расписания НЕ восстанавливаются автоматически (по issue): если до архивации
     * они были выключены — таковыми и останутся; если были активны — тоже не
     * трогаем, юзер сам пересмотрит. См. формулировку сообщения в Telegram-слое.
     */
    @Transactional
    public Plant restore(Long userId, Long plantId) {
        Plant plant = getArchivedOrThrow(userId, plantId);
        plant.setArchivedAt(null);
        Plant saved = plantRepository.save(plant);
        log.info("Restored plant {} from archive (user {})", plantId, userId);
        return saved;
    }

    /**
     * Окончательное удаление архивного растения. Каскадно подчищает
     * care_schedules, care_history, notifications_log, plant_anniversaries_sent
     * и пр. через FK ON DELETE CASCADE (см. миграции V1, V11, V19, V21).
     * Операция необратима.
     */
    @Transactional
    public void deleteForever(Long userId, Long plantId) {
        Plant plant = getArchivedOrThrow(userId, plantId);
        plantRepository.delete(plant);
        log.info("Permanently deleted plant {} (user {})", plantId, userId);
    }

    /**
     * Сколько раз растению делали полив за всё время. Используется для статистики
     * в карточке архива и в тексте годовщины. Для не-WATERING типов лучше использовать
     * прямой метод репозитория.
     */
    @Transactional(readOnly = true)
    public long countWaterings(Long plantId) {
        return careHistoryRepository.countActiveByPlantIdAndTaskType(plantId, TaskType.WATERING);
    }

    /**
     * Полная длительность «жизни с юзером» в полных месяцах — для строки
     * «N поливов за M месяцев» в карточке архива.
     *
     * <p>Базовая дата — {@code acquiredAt}, если задана, иначе
     * {@code createdAt}, чтобы у старых растений без acquired_at тоже что-то
     * показать. До-даты — {@code archivedAt}.
     *
     * <p>Возвращает {@code 0}, если архивации не было (на всякий случай) или
     * если интервал меньше месяца.
     */
    public long monthsLived(Plant plant) {
        if (plant.getArchivedAt() == null) {
            return 0L;
        }
        LocalDate startDate;
        if (plant.getAcquiredAt() != null) {
            startDate = plant.getAcquiredAt();
        } else if (plant.getCreatedAt() != null) {
            startDate = plant.getCreatedAt().toLocalDate();
        } else {
            return 0L;
        }
        LocalDate endDate = plant.getArchivedAt().toLocalDate();
        if (!endDate.isAfter(startDate)) {
            return 0L;
        }
        return ChronoUnit.MONTHS.between(startDate, endDate);
    }

    /**
     * Не относится к архиву напрямую, но используется в карточке архива:
     * проверка, что переданный юзер действительно владелец, нужна потому что
     * Telegram-слой уже валидирует chatId, но обвязка может прислать
     * чужой plantId через callback. Лучше явный fail-fast.
     */
    @Transactional(readOnly = true)
    public boolean isOwner(User user, Long plantId) {
        return plantRepository.findById(plantId)
                .map(p -> p.getUser().getId().equals(user.getId()))
                .orElse(false);
    }
}

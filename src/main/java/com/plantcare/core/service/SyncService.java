package com.plantcare.core.service;

import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.LocationRepository;
import com.plantcare.core.repository.PlantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Сервис инкрементального офлайн-синка (issue #91).
 *
 * <p>Собирает все сущности, изменившиеся после заданного момента,
 * и упаковывает их в единый {@link SyncResult}. Клиент использует
 * {@code serverTime} из ответа как {@code since} для следующего вызова,
 * что защищает от clock drift на устройстве.
 *
 * <p>Конфликт-резолюция: server timestamp wins (last write wins). CRDT
 * не реализован в фазе MVP — клиент получает финальное состояние сервера.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncService {

    private final PlantRepository plantRepository;
    private final LocationRepository locationRepository;
    private final CareHistoryRepository careHistoryRepository;

    /**
     * Выполняет инкрементальный синк для пользователя.
     *
     * @param userId  идентификатор пользователя
     * @param sinceTs момент, начиная с которого нужны изменения (UTC)
     * @return {@link SyncResult} с растениями, локациями, событиями ухода, удалениями и серверным временем
     */
    @Transactional(readOnly = true)
    public SyncResult sync(Long userId, Instant sinceTs) {
        LocalDateTime since = LocalDateTime.ofInstant(sinceTs, ZoneOffset.UTC);
        Instant serverTime = Instant.now();

        log.info("sync: userId={}, since={}", userId, since);

        List<Plant> plants = plantRepository.findChangedSince(userId, since);
        List<Location> locations = locationRepository.findChangedSince(userId, since);
        List<CareHistory> careEvents = careHistoryRepository.findChangedSince(userId, since);

        List<DeletionDto> deletions = buildDeletions(userId, since);

        log.info("sync result: userId={}, plants={}, locations={}, careEvents={}, deletions={}",
                userId, plants.size(), locations.size(), careEvents.size(), deletions.size());

        return new SyncResult(plants, locations, careEvents, deletions, serverTime);
    }

    private List<DeletionDto> buildDeletions(Long userId, LocalDateTime since) {
        var deletedPlants = plantRepository.findDeletedSince(userId, since).stream()
                .map(r -> new DeletionDto("plant", r.getId(),
                        r.getDeletedAt().toInstant(ZoneOffset.UTC)))
                .toList();

        var deletedLocations = locationRepository.findDeletedSince(userId, since).stream()
                .map(r -> new DeletionDto("location", r.getId(),
                        r.getDeletedAt().toInstant(ZoneOffset.UTC)))
                .toList();

        return java.util.stream.Stream
                .concat(deletedPlants.stream(), deletedLocations.stream())
                .sorted(java.util.Comparator.comparing(DeletionDto::deletedAt))
                .toList();
    }

    /**
     * Итоговый ответ синка.
     *
     * @param plants     активные растения, изменённые после since
     * @param locations  активные локации, изменённые после since
     * @param careEvents активные события ухода, изменённые после since
     * @param deletions  удалённые сущности
     * @param serverTime момент на сервере — клиент использует как since следующего вызова
     */
    public record SyncResult(
            List<Plant> plants,
            List<Location> locations,
            List<CareHistory> careEvents,
            List<DeletionDto> deletions,
            Instant serverTime
    ) {}

    /**
     * Запись об удалении сущности для sync-ответа.
     *
     * @param entityType тип сущности: {@code "plant"} или {@code "location"}
     * @param id         идентификатор сущности
     * @param deletedAt  момент удаления (UTC)
     */
    public record DeletionDto(
            String entityType,
            Long id,
            Instant deletedAt
    ) {}
}

package com.plantcare.api.v1;

import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.generated.SyncApi;
import com.plantcare.api.generated.model.CareEventType;
import com.plantcare.api.generated.model.SyncCareEventDto;
import com.plantcare.api.generated.model.SyncDeletionDto;
import com.plantcare.api.generated.model.SyncLocationDto;
import com.plantcare.api.generated.model.SyncPlantDto;
import com.plantcare.api.generated.model.SyncResponse;
import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.service.SyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Контроллер офлайн-синхронизации (issue #91).
 *
 * <p>GET /api/v1/sync возвращает инкрементальные изменения:
 * всё, что изменилось с момента {@code since}. Если {@code since} не задан —
 * full sync (все данные пользователя, эпоха = 1970-01-01T00:00:00Z).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class SyncController implements SyncApi {

    /** Эпоха для full-sync (since не передан). */
    private static final Instant EPOCH = Instant.EPOCH;

    private final SyncService syncService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public SyncResponse getSync(OffsetDateTime since) {
        Long userId = currentUserProvider.currentUserId();
        Instant sinceInstant = since != null ? since.toInstant() : EPOCH;

        log.info("GET /api/v1/sync: userId={}, since={}", userId, sinceInstant);

        SyncService.SyncResult result = syncService.sync(userId, sinceInstant);

        List<SyncPlantDto> plants = result.plants().stream()
                .map(SyncController::toPlantDto)
                .toList();

        List<SyncLocationDto> locations = result.locations().stream()
                .map(SyncController::toLocationDto)
                .toList();

        List<SyncCareEventDto> careEvents = result.careEvents().stream()
                .map(SyncController::toCareEventDto)
                .toList();

        List<SyncDeletionDto> deletions = result.deletions().stream()
                .map(SyncController::toDeletionDto)
                .toList();

        return new SyncResponse(
                plants,
                locations,
                careEvents,
                deletions,
                result.serverTime().atOffset(ZoneOffset.UTC)
        );
    }

    private static SyncPlantDto toPlantDto(Plant plant) {
        SyncPlantDto dto = new SyncPlantDto(
                plant.getId(),
                plant.getName(),
                plant.getLocation() != null ? plant.getLocation().getId() : null,
                plant.isArchived(),
                plant.getUpdatedAt() != null
                        ? plant.getUpdatedAt().atOffset(ZoneOffset.UTC)
                        : plant.getCreatedAt().atOffset(ZoneOffset.UTC)
        );

        dto.notes(plant.getNotes())
           .clientId(plant.getClientId());

        if (plant.getLocation() != null) {
            dto.locationName(plant.getLocation().getName());
        }

        if (plant.getSpecies() != null) {
            dto.speciesId(plant.getSpecies().getId())
               .speciesName(plant.getSpecies().getName());
        }

        return dto;
    }

    private static SyncLocationDto toLocationDto(Location location) {
        return new SyncLocationDto(
                location.getId(),
                location.getName(),
                location.isDefaultLocation(),
                location.getUpdatedAt() != null
                        ? location.getUpdatedAt().atOffset(ZoneOffset.UTC)
                        : location.getCreatedAt().atOffset(ZoneOffset.UTC)
        )
                .emoji(location.getEmoji())
                .clientId(location.getClientId());
    }

    private static SyncCareEventDto toCareEventDto(CareHistory history) {
        SyncCareEventDto dto = new SyncCareEventDto(
                history.getId(),
                history.getPlant().getId(),
                fromTaskType(history.getTaskType()),
                history.getDoneAt().atOffset(ZoneOffset.UTC),
                history.isOnTime(),
                history.getUpdatedAt() != null
                        ? history.getUpdatedAt().atOffset(ZoneOffset.UTC)
                        : history.getCreatedAt().atOffset(ZoneOffset.UTC)
        );

        dto.note(history.getNote())
           .clientId(history.getClientId());

        return dto;
    }

    private static SyncDeletionDto toDeletionDto(SyncService.DeletionDto d) {
        SyncDeletionDto dto = new SyncDeletionDto(
                SyncDeletionDto.EntityTypeEnum.fromValue(d.entityType()),
                d.id(),
                d.deletedAt().atOffset(ZoneOffset.UTC)
        );
        return dto;
    }

    private static CareEventType fromTaskType(TaskType taskType) {
        return switch (taskType) {
            case WATERING -> CareEventType.WATER;
            case MISTING -> CareEventType.SPRAY;
            case FERTILIZING -> CareEventType.FERTILIZE;
            case SOIL_CHECK -> CareEventType.SOIL_CHECK;
        };
    }
}

package com.plantcare.api.v1;

import com.plantcare.api.generated.CareEventsApi;
import com.plantcare.api.generated.model.CareEventResponse;
import com.plantcare.api.generated.model.CareEventType;
import com.plantcare.api.generated.model.CreateCareEventRequest;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;

/**
 * REST API для регистрации и отмены событий ухода за растениями (issue #86).
 *
 * <p>Документация и mapping живут в сгенерированном {@link CareEventsApi} (см. openapi.yaml).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class CareEventController implements CareEventsApi {

    private final com.plantcare.core.service.CareEventApiService careEventApiService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public CareEventResponse createCareEvent(CreateCareEventRequest req) {
        User user = currentUserProvider.currentUser();
        log.info("POST /api/v1/care-events: userId={}, plantId={}, type={}, clientId={}",
                user.getId(), req.getPlantId(), req.getType(), req.getClientId());

        CareHistory history = careEventApiService.registerEvent(
                user.getId(),
                req.getPlantId(),
                toTaskType(req.getType()),
                req.getPerformedAt().toInstant(),
                req.getNote(),
                req.getClientId()
        );

        return toResponse(history);
    }

    @Override
    public void cancelCareEvent(Long id) {
        User user = currentUserProvider.currentUser();
        log.info("DELETE /api/v1/care-events/{}: userId={}", id, user.getId());

        careEventApiService.cancelEvent(user.getId(), id);
    }

    static CareEventResponse toResponse(CareHistory history) {
        return toResponse(history, history.getPlant());
    }

    /**
     * Вариант для вызывающих, у которых растение уже загружено отдельно.
     *
     * <p>Нужен там, где {@code history} приходит из запроса, который не тянет
     * {@code plant} (например нативный листинг истории с реальным offset), —
     * тогда {@code history.getPlant().getName()} вне транзакции дал бы
     * {@code LazyInitializationException: no session}. Здесь имя/id берутся из
     * переданного {@code plant}, а не из ленивого прокси.
     */
    static CareEventResponse toResponse(CareHistory history, Plant plant) {
        return new CareEventResponse(
                history.getId(),
                plant.getId(),
                plant.getName(),
                fromTaskType(history.getTaskType()),
                history.getDoneAt().atOffset(ZoneOffset.UTC),
                history.isOnTime()
        )
                .note(history.getNote())
                .clientId(history.getClientId());
    }

    private static TaskType toTaskType(CareEventType apiType) {
        return switch (apiType) {
            case WATER -> TaskType.WATERING;
            case SPRAY -> TaskType.MISTING;
            case FERTILIZE -> TaskType.FERTILIZING;
        };
    }

    private static CareEventType fromTaskType(TaskType taskType) {
        return switch (taskType) {
            case WATERING -> CareEventType.WATER;
            case MISTING -> CareEventType.SPRAY;
            case FERTILIZING -> CareEventType.FERTILIZE;
            case SOIL_CHECK -> throw new IllegalStateException(
                    "SOIL_CHECK cannot be represented as CareEventType, filter before mapping");
        };
    }
}

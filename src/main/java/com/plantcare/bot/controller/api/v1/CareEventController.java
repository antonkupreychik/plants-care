package com.plantcare.bot.controller.api.v1;

import com.plantcare.bot.api.generated.CareEventsApi;
import com.plantcare.bot.api.generated.model.CareEventResponse;
import com.plantcare.bot.api.generated.model.CareEventType;
import com.plantcare.bot.api.generated.model.CreateCareEventRequest;
import com.plantcare.bot.controller.api.UserApiResolver;
import com.plantcare.core.domain.CareHistory;
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
    private final UserApiResolver userApiResolver;

    @Override
    public CareEventResponse createCareEvent(Long chatId, CreateCareEventRequest req) {
        User user = userApiResolver.resolve(chatId);
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
    public void cancelCareEvent(Long chatId, Long id) {
        User user = userApiResolver.resolve(chatId);
        log.info("DELETE /api/v1/care-events/{}: userId={}", id, user.getId());

        careEventApiService.cancelEvent(user.getId(), id);
    }

    static CareEventResponse toResponse(CareHistory history) {
        return new CareEventResponse(
                history.getId(),
                history.getPlant().getId(),
                history.getPlant().getName(),
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

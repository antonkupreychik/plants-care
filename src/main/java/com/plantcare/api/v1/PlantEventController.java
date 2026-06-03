package com.plantcare.api.v1;

import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.generated.PlantEventsApi;
import com.plantcare.api.generated.model.PlantEventDto;
import com.plantcare.api.generated.model.PlantEventPageResponse;
import com.plantcare.api.generated.model.PlantEventRequest;
import com.plantcare.core.domain.PlantEvent;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.PlantEventType;
import com.plantcare.core.service.PlantEventService;
import com.plantcare.core.service.PlantEventService.AddResult;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.util.List;

/**
 * REST API журнала событий растения (issue #220): чтение и добавление редких
 * разовых событий (пересадка, замена грунта, обрезка, обработка от вредителей).
 *
 * <p>Контроллер тонкий: вся логика (дедуп, проверка владельца, пагинация) —
 * в {@link PlantEventService}. Документация и mapping живут в сгенерированном
 * {@link PlantEventsApi} (см. openapi.yaml).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PlantEventController implements PlantEventsApi {

    private final PlantEventService plantEventService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public PlantEventPageResponse getPlantEvents(Long id, Integer limit, Integer offset) {
        User user = currentUserProvider.currentUser();

        // limit уже валидирован [1,100] на входе (@Min/@Max в сгенерированном API);
        // offset нормализуем на всякий случай, как в остальных листингах.
        int safeOffset = Math.max(0, offset);

        log.info("GET /api/v1/plants/{}/events: userId={}, limit={}, offset={}",
                id, user.getId(), limit, safeOffset);

        Page<PlantEvent> page = plantEventService.getEventsPage(user, id, safeOffset, limit);

        List<PlantEventDto> items = page.getContent().stream()
                .map(PlantEventController::toDto)
                .toList();

        return new PlantEventPageResponse(items, page.getTotalElements(), limit, safeOffset);
    }

    @Override
    public PlantEventDto createPlantEvent(Long id, PlantEventRequest request) {
        User user = currentUserProvider.currentUser();

        // eventType валиден по enum (неизвестное значение отсекается десериализацией → 422),
        // здесь маппим API-enum в доменный по имени — наборы значений идентичны.
        PlantEventType eventType = PlantEventType.valueOf(request.getEventType().getValue());

        log.info("POST /api/v1/plants/{}/events: userId={}, eventType={}",
                id, user.getId(), eventType);

        AddResult result = plantEventService.addEvent(user, id, eventType);

        if (result.wasNotFound()) {
            throw new EntityNotFoundException(
                    "Plant not found: id=" + id + " for userId=" + user.getId());
        }
        if (result.wasDuplicate()) {
            // Повтор того же типа в окне дедупа (60 сек) — двойное нажатие.
            throw new IllegalStateException(
                    "Duplicate plant event: type=" + eventType + " within dedup window");
        }

        return toDto(result.event());
    }

    /**
     * Доменное событие → API DTO. {@code eventDate} в БД хранится как UTC
     * (см. CLAUDE.md), поэтому отдаём со смещением UTC. {@code comment} в v1
     * всегда null — колонка зарезервирована под будущее.
     */
    private static PlantEventDto toDto(PlantEvent event) {
        return new PlantEventDto(
                event.getId(),
                com.plantcare.api.generated.model.PlantEventType.fromValue(event.getEventType().name()),
                event.getEventDate().atOffset(ZoneOffset.UTC)
        ).comment(event.getComment());
    }
}

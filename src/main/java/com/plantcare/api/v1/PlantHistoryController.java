package com.plantcare.api.v1;

import com.plantcare.api.generated.PlantHistoryApi;
import com.plantcare.api.generated.model.CareEventResponse;
import com.plantcare.api.generated.model.CareEventType;
import com.plantcare.api.generated.model.PlantHistoryResponse;
import com.plantcare.api.generated.model.PlantHistorySummaryDto;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.service.CareHistoryService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API для получения истории ухода за растением (issue #86, #206).
 *
 * <p>Документация и mapping живут в сгенерированном {@link PlantHistoryApi}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PlantHistoryController implements PlantHistoryApi {

    private final CareHistoryService careHistoryService;
    private final PlantRepository plantRepository;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public PlantHistoryResponse getPlantHistory(Long id, Integer limit, Integer offset, CareEventType type) {
        User user = currentUserProvider.currentUser();

        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(user.getId(), id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Plant not found: id=" + id + " for userId=" + user.getId()));

        log.info("GET /api/v1/plants/{}/history: userId={}, limit={}, offset={}, type={}",
                id, user.getId(), limit, offset, type);

        int safeOffset = Math.max(0, offset);
        TaskType taskType = toTaskType(type);

        List<CareHistory> items = careHistoryService.getHistoryPageWithLimit(
                plant.getId(), safeOffset, limit, taskType);
        long total = careHistoryService.countHistory(plant.getId(), taskType);

        // Все записи принадлежат одному загруженному выше растению; мапим из него,
        // а не из ленивого history.getPlant() — нативный листинг истории plant не
        // фетчит, и обращение к прокси вне транзакции дало бы no-session (issue #86).
        // SOIL_CHECK теперь публичный тип (issue #222) и возвращается наравне с
        // остальными — мобильный дневник показывает проверки почвы.
        List<CareEventResponse> responseItems = items.stream()
                .map(h -> CareEventController.toResponse(h, plant))
                .toList();

        return new PlantHistoryResponse(responseItems, total, limit, safeOffset);
    }

    @Override
    public PlantHistorySummaryDto getPlantHistorySummary(Long id) {
        User user = currentUserProvider.currentUser();

        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(user.getId(), id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Plant not found: id=" + id + " for userId=" + user.getId()));

        log.info("GET /api/v1/plants/{}/history/summary: userId={}", id, user.getId());

        CareHistoryService.HistorySummary summary = careHistoryService.getHistorySummary(plant.getId());

        // Преобразуем Map<TaskType, Long> → Map<String, Long> с ключами CareEventType
        // (SOIL_CHECK уже отфильтрован в сервисе, поэтому fromTaskType всегда даёт результат).
        Map<String, Long> byType = summary.byType().entrySet().stream()
                .collect(Collectors.toMap(
                        e -> fromTaskType(e.getKey()).getValue(),
                        Map.Entry::getValue
                ));

        return new PlantHistorySummaryDto(
                summary.total(),
                summary.onTimeCount(),
                summary.onTimePercent(),
                byType
        );
    }

    /**
     * Конвертирует API {@link CareEventType} в доменный {@link TaskType}.
     * {@code null} → {@code null} (нет фильтра).
     */
    private static TaskType toTaskType(CareEventType type) {
        if (type == null) return null;
        return switch (type) {
            case WATER -> TaskType.WATERING;
            case SPRAY -> TaskType.MISTING;
            case FERTILIZE -> TaskType.FERTILIZING;
            case SOIL_CHECK -> TaskType.SOIL_CHECK;
        };
    }

    /**
     * Конвертирует доменный {@link TaskType} в API {@link CareEventType}.
     *
     * <p>Используется только в сводке (summary), где SOIL_CHECK заранее
     * отфильтрован сервисом — но маппинг тотальный, чтобы не падать, если
     * фильтр когда-нибудь снимут (issue #222).
     */
    private static CareEventType fromTaskType(TaskType type) {
        return switch (type) {
            case WATERING -> CareEventType.WATER;
            case MISTING -> CareEventType.SPRAY;
            case FERTILIZING -> CareEventType.FERTILIZE;
            case SOIL_CHECK -> CareEventType.SOIL_CHECK;
        };
    }
}

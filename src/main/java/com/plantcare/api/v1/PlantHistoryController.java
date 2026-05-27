package com.plantcare.api.v1;

import com.plantcare.api.generated.PlantHistoryApi;
import com.plantcare.api.generated.model.CareEventResponse;
import com.plantcare.api.generated.model.PlantHistoryResponse;
import com.plantcare.api.UserApiResolver;
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

/**
 * REST API для получения истории ухода за растением (issue #86).
 *
 * <p>Документация и mapping живут в сгенерированном {@link PlantHistoryApi}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PlantHistoryController implements PlantHistoryApi {

    private final CareHistoryService careHistoryService;
    private final PlantRepository plantRepository;
    private final UserApiResolver userApiResolver;

    @Override
    public PlantHistoryResponse getPlantHistory(Long chatId, Long id, Integer limit, Integer offset) {
        User user = userApiResolver.resolve(chatId);

        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(user.getId(), id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Plant not found: id=" + id + " for userId=" + user.getId()));

        log.info("GET /api/v1/plants/{}/history: userId={}, limit={}, offset={}",
                id, user.getId(), limit, offset);

        int safeOffset = Math.max(0, offset);

        List<CareHistory> items = careHistoryService.getHistoryPageWithLimit(
                plant.getId(), safeOffset, limit);
        long total = careHistoryService.countHistory(plant.getId());

        // Все записи принадлежат одному загруженному выше растению; мапим из него,
        // а не из ленивого history.getPlant() — нативный листинг истории plant не
        // фетчит, и обращение к прокси вне транзакции дало бы no-session (issue #86).
        List<CareEventResponse> responseItems = items.stream()
                .filter(h -> h.getTaskType() != TaskType.SOIL_CHECK)
                .map(h -> CareEventController.toResponse(h, plant))
                .toList();

        return new PlantHistoryResponse(responseItems, total, limit, safeOffset);
    }
}

package com.plantcare.bot.controller.api.v1;

import com.plantcare.bot.api.generated.PlantHistoryApi;
import com.plantcare.bot.api.generated.model.CareEventResponse;
import com.plantcare.bot.api.generated.model.PlantHistoryResponse;
import com.plantcare.bot.controller.api.UserApiResolver;
import com.plantcare.bot.domain.CareHistory;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.service.CareHistoryService;
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

        List<CareEventResponse> responseItems = items.stream()
                .filter(h -> h.getTaskType() != TaskType.SOIL_CHECK)
                .map(CareEventController::toResponse)
                .toList();

        return new PlantHistoryResponse(responseItems, total, limit, safeOffset);
    }
}

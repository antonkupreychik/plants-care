package com.plantcare.api.v1;

import com.plantcare.api.generated.StatsApi;
import com.plantcare.api.generated.model.StreakResponse;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.service.CareHistoryService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API статистики (issue #86).
 *
 * <p>Документация и mapping живут в сгенерированном {@link StatsApi}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class StatsController implements StatsApi {

    private final CareHistoryService careHistoryService;
    private final PlantRepository plantRepository;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public StreakResponse getPlantStreak(Long plantId) {
        User user = currentUserProvider.currentUser();
        log.info("GET /api/v1/stats/streak: userId={}, plantId={}", user.getId(), plantId);

        plantRepository.findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Plant not found: id=" + plantId + " for userId=" + user.getId()));

        int streak = careHistoryService.computePlantStreak(plantId);

        return new StreakResponse(plantId, streak);
    }
}

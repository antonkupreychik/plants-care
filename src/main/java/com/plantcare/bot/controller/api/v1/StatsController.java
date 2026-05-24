package com.plantcare.bot.controller.api.v1;

import com.plantcare.bot.api.generated.StatsApi;
import com.plantcare.bot.api.generated.model.StreakResponse;
import com.plantcare.bot.controller.api.UserApiResolver;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.service.CareHistoryService;
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
    private final UserApiResolver userApiResolver;

    @Override
    public StreakResponse getPlantStreak(Long chatId, Long plantId) {
        User user = userApiResolver.resolve(chatId);
        log.info("GET /api/v1/stats/streak: userId={}, plantId={}", user.getId(), plantId);

        plantRepository.findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Plant not found: id=" + plantId + " for userId=" + user.getId()));

        int streak = careHistoryService.computePlantStreak(plantId);

        return new StreakResponse(plantId, streak);
    }
}

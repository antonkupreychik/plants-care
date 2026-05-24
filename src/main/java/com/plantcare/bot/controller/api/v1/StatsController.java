package com.plantcare.bot.controller.api.v1;

import com.plantcare.bot.controller.api.UserApiResolver;
import com.plantcare.bot.controller.api.v1.dto.StreakResponse;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.service.CareHistoryService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API для статистики ухода за растениями (issue #86).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/stats")
@RequiredArgsConstructor
public class StatsController {

    private final CareHistoryService careHistoryService;
    private final PlantRepository plantRepository;
    private final UserApiResolver userApiResolver;

    /**
     * GET /api/v1/stats/streak?plantId=123 — текущий стрик растения.
     *
     * <p>Стрик = количество последовательных выполнений без пропусков (on_time подряд).
     * Подробнее — в {@link CareHistoryService#computePlantStreak(Long)}.
     *
     * <p>Проверяет ownership: растение должно принадлежать пользователю из заголовка.
     *
     * @param plantId id растения
     * @return объект с plantId и streak
     */
    @GetMapping("/streak")
    public StreakResponse streak(
            @RequestParam Long plantId,
            @RequestHeader("X-Chat-Id") Long chatId
    ) {
        User user = userApiResolver.resolve(chatId);
        log.info("GET /api/v1/stats/streak: userId={}, plantId={}", user.getId(), plantId);

        // Проверка ownership
        plantRepository.findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Plant not found: id=" + plantId + " for userId=" + user.getId()));

        int streak = careHistoryService.computePlantStreak(plantId);

        return new StreakResponse(plantId, streak);
    }
}

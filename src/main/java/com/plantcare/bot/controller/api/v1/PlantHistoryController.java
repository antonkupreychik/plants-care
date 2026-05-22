package com.plantcare.bot.controller.api.v1;

import com.plantcare.bot.controller.api.UserApiResolver;
import com.plantcare.bot.controller.api.v1.dto.CareEventResponse;
import com.plantcare.bot.controller.api.v1.dto.PlantHistoryResponse;
import com.plantcare.bot.domain.CareHistory;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.service.CareHistoryService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API для получения истории ухода за растением (issue #86).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/plants")
@RequiredArgsConstructor
public class PlantHistoryController {

    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 100;

    private final CareHistoryService careHistoryService;
    private final PlantRepository plantRepository;
    private final UserApiResolver userApiResolver;

    /**
     * GET /api/v1/plants/{id}/history?limit=20&offset=0
     *
     * <p>Limit/offset пагинация активных (не отменённых) записей истории.
     * Проверяет ownership: растение должно принадлежать пользователю из заголовка.
     *
     * @param limit  количество записей на странице [1, 100], default 20
     * @param offset смещение от начала, default 0
     * @return страница истории с метаданными пагинации
     */
    @GetMapping("/{id}/history")
    public PlantHistoryResponse history(
            @PathVariable Long id,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestHeader("X-Chat-Id") Long chatId
    ) {
        User user = userApiResolver.resolve(chatId);

        // Валидация limit
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "limit must be between " + MIN_LIMIT + " and " + MAX_LIMIT);
        }

        // Проверка ownership
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
                .map(CareEventResponse::from)
                .toList();

        return new PlantHistoryResponse(responseItems, total, limit, safeOffset);
    }
}

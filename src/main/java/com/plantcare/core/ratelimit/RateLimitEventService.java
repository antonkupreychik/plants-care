package com.plantcare.core.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for persisting and querying rate-limit events (issue #92).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RateLimitEventService {

    private final RateLimitEventRepository repository;

    /**
     * Records a 429 event. Swallows exceptions to avoid disrupting the request pipeline.
     */
    public void record(String ip, Long userId, String endpoint, String limitType) {
        try {
            repository.save(ip, userId, endpoint, limitType);
            log.warn("Rate limit exceeded: ip={} userId={} endpoint={} type={}", ip, userId, endpoint, limitType);
        } catch (Exception e) {
            log.error("Failed to persist rate-limit event: {}", e.getMessage(), e);
        }
    }

    /**
     * Returns top violators for the last {@code hours} hours.
     */
    public List<TopViolatorDto> findTopViolators(int hours, int limit) {
        return repository.findTopViolatorsLastHours(hours, limit);
    }

    /**
     * Total event count for the last {@code hours} hours.
     */
    public long countLastHours(int hours) {
        return repository.countLastHours(hours);
    }
}

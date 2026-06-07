package com.plantcare.core.ratelimit;

import java.time.Instant;

/**
 * Top rate-limit violator projection for admin dashboard (issue #92).
 */
public record TopViolatorDto(
        String ip,
        Long userId,
        long eventCount,
        Instant lastSeen
) {}

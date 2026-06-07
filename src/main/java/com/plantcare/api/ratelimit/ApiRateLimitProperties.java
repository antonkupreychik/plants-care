package com.plantcare.api.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rate-limiting configuration for the REST API (issue #92).
 *
 * <pre>
 * plantcare.api.rate-limit:
 *   unauthenticated-per-minute: 100  # req/min per IP
 *   authenticated-per-minute:   1000 # req/min per user
 *   auth-endpoint-per-minute:   10   # req/min per IP for /auth/apple, /auth/google
 *   magic-link-per-minute:      3    # req/min per IP for /auth/email/request
 *   magic-link-per-hour:        5    # req/hour per email for /auth/email/request
 *   max-buckets:                100000 # max distinct keys (IP or user-id)
 * </pre>
 */
@ConfigurationProperties(prefix = "plantcare.api.rate-limit")
public record ApiRateLimitProperties(
        int unauthenticatedPerMinute,
        int authenticatedPerMinute,
        int authEndpointPerMinute,
        int magicLinkPerMinute,
        int magicLinkPerHour,
        long maxBuckets
) {
    public ApiRateLimitProperties {
        if (unauthenticatedPerMinute <= 0) unauthenticatedPerMinute = 100;
        if (authenticatedPerMinute <= 0) authenticatedPerMinute = 1000;
        if (authEndpointPerMinute <= 0) authEndpointPerMinute = 10;
        if (magicLinkPerMinute <= 0) magicLinkPerMinute = 3;
        if (magicLinkPerHour <= 0) magicLinkPerHour = 5;
        if (maxBuckets <= 0) maxBuckets = 100_000;
    }
}

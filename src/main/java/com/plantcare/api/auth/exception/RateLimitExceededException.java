package com.plantcare.api.auth.exception;

import lombok.Getter;

/**
 * Бросается, когда исчерпан лимит запросов magic-link (issue #88). Маппится в
 * HTTP 429 с заголовком {@code Retry-After} через {@code WebApiExceptionHandler}.
 */
@Getter
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}

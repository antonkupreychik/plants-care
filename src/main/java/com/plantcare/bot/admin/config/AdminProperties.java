package com.plantcare.bot.admin.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Конфигурация админ-панели из env-переменных.
 * Пустой username или hash → admin disabled (см. DisabledAdminController).
 */
@Getter
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "admin")
public class AdminProperties {

    private final String username;
    private final String passwordBcryptHash;
    private final int sessionTimeoutHours;
    private final RateLimit rateLimit;
    private final Dashboard dashboard;

    @Getter
    @RequiredArgsConstructor
    public static class RateLimit {
        private final int maxAttempts;
        private final int windowSeconds;
    }

    @Getter
    @RequiredArgsConstructor
    public static class Dashboard {
        private final String timezone;
        private final int queryExecutorPoolSize;
    }

    public boolean isEnabled() {
        return username != null && !username.isBlank()
                && passwordBcryptHash != null && !passwordBcryptHash.isBlank();
    }
}

package com.plantcare.admin.config;

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
    private final Storage storage;

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

    /**
     * Параметры страницы /admin/storage (issue #101).
     *
     * <p>Хранилище — S3-совместимое (Railway), не Cloudflare R2, как было
     * написано в issue: проект поехал на AWS SDK v2 в issue #90. Поэтому цена
     * вынесена в конфиг, а не захардкожена под R2-тариф.
     */
    @Getter
    @RequiredArgsConstructor
    public static class Storage {

        /** Сколько дней держим soft-deleted фото до физического удаления из бакета. */
        private final int retentionDays;

        /** Цена хранения за ГБ в месяц (USD) — для прикидки расходов на дашборде. */
        private final double pricePerGbMonth;

        /** Верхняя граница пачки за один прогон чистки — чтобы не выгребать бакет целиком. */
        private final int purgeBatchSize;

        /** Сколько последних загрузок показываем на странице (размер страницы). */
        private final int recentPageSize;
    }

    public boolean isEnabled() {
        return username != null && !username.isBlank()
                && passwordBcryptHash != null && !passwordBcryptHash.isBlank();
    }
}

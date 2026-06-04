package com.plantcare.api.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Конфигурация Universal Links (iOS) и App Links (Android) для magic-link диплинков.
 * Issue #215 (ADR-011, #88).
 *
 * <p>Значения берутся только из env — они различаются на dev/prod и
 * зависят от сертификатов мобильного приложения.
 *
 * <p>Маппинг env: {@code DEEP_LINK_IOS_*} / {@code DEEP_LINK_ANDROID_*}.
 * Пример:
 * <pre>
 *   DEEP_LINK_IOS_APP_IDS=TEAMID1234.com.example.plantcare
 *   DEEP_LINK_ANDROID_PACKAGE_NAME=com.example.plantcare
 *   DEEP_LINK_ANDROID_SHA256_CERT_FINGERPRINTS=AA:BB:CC:...,DD:EE:FF:...
 * </pre>
 */
@Getter
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "plantcare.deep-link")
public class DeepLinkProperties {

    /** Настройки iOS Universal Links / AASA. */
    private final Ios ios;

    /** Настройки Android App Links / assetlinks.json. */
    private final Android android;

    /**
     * iOS Apple App Site Association (AASA).
     */
    @Getter
    @RequiredArgsConstructor
    public static class Ios {
        /**
         * Список appID в формате {@code <TeamID>.<BundleId>}.
         * Пример: {@code ["TEAMID1234.com.example.plantcare"]}.
         * Может содержать несколько, если dev/prod разные bundle id.
         */
        private final List<String> appIds;

        /**
         * Пути, которые AASA разрешает открывать в приложении.
         * Минимум: {@code ["/auth/verify*"]}.
         */
        private final List<String> paths;
    }

    /**
     * Android Digital Asset Links (assetlinks.json).
     */
    @Getter
    @RequiredArgsConstructor
    public static class Android {
        /**
         * Package name приложения. Пример: {@code com.example.plantcare}.
         */
        private final String packageName;

        /**
         * SHA-256 отпечатки подписи приложения (debug + release).
         * Формат: {@code AA:BB:CC:...} (hex, разделённый двоеточиями).
         */
        private final List<String> sha256CertFingerprints;
    }
}

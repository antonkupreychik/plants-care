package com.plantcare.core.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Конфигурация push-канала (issue #175, ADR-014).
 *
 * <p>Глобальный тумблер {@code push.enabled} (по умолчанию {@code false}) позволяет
 * запускать приложение без реальных FCM / APNs-ключей: все отправки поглощаются
 * {@link NoopPushSender}.
 *
 * <p>Пример конфигурации ({@code application.yml}):
 * <pre>
 * push:
 *   enabled: true          # включить реальную отправку
 *   fcm:
 *     server-key: ${FCM_SERVER_KEY:}
 *   apns:
 *     key-id:    ${APNS_KEY_ID:}
 *     team-id:   ${APNS_TEAM_ID:}
 *     bundle-id: ${APNS_BUNDLE_ID:}
 *     private-key-path: ${APNS_PRIVATE_KEY_PATH:}
 * </pre>
 */
@ConfigurationProperties(prefix = "push")
@Getter
@Setter
public class PushProperties {

    /**
     * Глобальный тумблер push-уведомлений. По умолчанию {@code false} —
     * используется {@link NoopPushSender}, реальные провайдеры не инициализируются.
     */
    private boolean enabled = false;

    private Fcm fcm = new Fcm();
    private Apns apns = new Apns();

    @Getter
    @Setter
    public static class Fcm {
        /** FCM Server Key (Legacy HTTP API) или путь к JSON-ключу сервисного аккаунта (HTTP v1). */
        private String serverKey = "";
    }

    @Getter
    @Setter
    public static class Apns {
        /** APNs Key ID из Apple Developer Console. */
        private String keyId = "";
        /** Apple Team ID. */
        private String teamId = "";
        /** Bundle ID мобильного приложения. */
        private String bundleId = "";
        /** Путь к .p8 private-key файлу (или classpath:). */
        private String privateKeyPath = "";
    }
}

package com.plantcare.core.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Конфигурация push-канала (issue #175 / #176, ADR-016).
 *
 * <p>Глобальный тумблер {@code push.enabled} (по умолчанию {@code false}) позволяет
 * запускать приложение без реальных FCM-кредов: все отправки поглощаются
 * {@link NoopPushSender}. При {@code push.enabled=true} активируется FCM-шлюз
 * ({@code com.plantcare.delivery.push.FcmPushSender}) через Firebase Admin SDK (HTTP v1).
 *
 * <p>Канал единый — FCM для iOS и Android (FCM сам маршрутизирует на APNs для iOS-токенов),
 * без отдельной APNs-интеграции и без legacy server-key (см. ADR-016).
 *
 * <p>Пример конфигурации ({@code application.yml}):
 * <pre>
 * push:
 *   enabled: true                                   # включить реальную отправку
 *   fcm:
 *     credentials-json: ${FCM_CREDENTIALS_JSON:}    # инлайн service-account JSON
 *     credentials-path: ${GOOGLE_APPLICATION_CREDENTIALS:}  # ИЛИ путь к JSON-файлу
 *     project-id:       ${FCM_PROJECT_ID:}          # опционально (обычно берётся из кредов)
 * </pre>
 */
@ConfigurationProperties(prefix = "push")
@Getter
@Setter
public class PushProperties {

    /**
     * Глобальный тумблер push-уведомлений. По умолчанию {@code false} —
     * используется {@link NoopPushSender}, Firebase Admin SDK не инициализируется.
     */
    private boolean enabled = false;

    private Fcm fcm = new Fcm();

    @Getter
    @Setter
    public static class Fcm {
        /**
         * Инлайн-JSON service-account ключа (HTTP v1). Имеет приоритет над {@link #credentialsPath}.
         * Удобно для Railway, где секрет хранится как одна env-переменная.
         */
        private String credentialsJson = "";

        /**
         * Путь к JSON-файлу service-account ключа (HTTP v1). Используется, если
         * {@link #credentialsJson} пуст. Совместим с {@code GOOGLE_APPLICATION_CREDENTIALS}.
         */
        private String credentialsPath = "";

        /**
         * GCP/Firebase project id. Опционально — обычно выводится из кредов;
         * задаётся, только если креды его не содержат.
         */
        private String projectId = "";
    }
}

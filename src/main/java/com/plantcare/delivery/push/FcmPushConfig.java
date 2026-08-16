package com.plantcare.delivery.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.plantcare.core.config.PushProperties;
import com.plantcare.core.service.PushSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Spring-конфигурация реального FCM-канала (issue #176 / ADR-016) в слое доставки.
 *
 * <p>Регистрирует {@link FcmPushSender} как бин {@link PushSender} только при
 * {@code push.enabled=true}. При выключенном тумблере (дефолт) активен
 * {@link com.plantcare.core.config.NoopPushSender} из core, а Firebase Admin SDK
 * вообще не инициализируется (нет обращения к сети/кредам).
 *
 * <p>Эта конфигурация живёт в {@code com.plantcare.delivery.push}, а не в {@code core}:
 * выбор реальной реализации — деталь слоя доставки, поэтому ArchUnit-правило
 * {@code core ∌ delivery} остаётся зелёным (core знает только про порт и Noop).
 *
 * <p>Инициализация {@link FirebaseApp} идемпотентна: SDK запрещает создавать приложение
 * по умолчанию дважды, поэтому переиспользуем уже существующий инстанс, если он есть.
 *
 * <p>Креды сервисного аккаунта берутся (в порядке приоритета):
 * <ol>
 *   <li>инлайн-JSON из {@code push.fcm.credentials-json} (env {@code FCM_CREDENTIALS_JSON});</li>
 *   <li>путь к JSON-файлу из {@code push.fcm.credentials-path} (env {@code GOOGLE_APPLICATION_CREDENTIALS});</li>
 *   <li>стандартный Application Default Credentials (ADC) окружения.</li>
 * </ol>
 */
@Configuration
@ConditionalOnProperty(name = "push.enabled", havingValue = "true")
@Slf4j
public class FcmPushConfig {

    /** Скоуп для FCM HTTP v1. */
    private static final String FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    @Bean
    public FirebaseApp firebaseApp(PushProperties pushProperties) throws IOException {
        if (!FirebaseApp.getApps().isEmpty()) {
            log.info("FirebaseApp already initialized — reusing existing instance");
            return FirebaseApp.getInstance();
        }

        PushProperties.Fcm fcm = pushProperties.getFcm();
        GoogleCredentials credentials = resolveCredentials(fcm).createScoped(List.of(FCM_SCOPE));

        FirebaseOptions.Builder optionsBuilder = FirebaseOptions.builder()
                .setCredentials(credentials);
        if (StringUtils.hasText(fcm.getProjectId())) {
            optionsBuilder.setProjectId(fcm.getProjectId());
        }

        FirebaseApp app = FirebaseApp.initializeApp(optionsBuilder.build());
        log.info("FirebaseApp initialized for FCM push (push.enabled=true), name={}", app.getName());
        return app;
    }

    @Bean
    public FirebaseMessaging firebaseMessaging(FirebaseApp firebaseApp) {
        return FirebaseMessaging.getInstance(firebaseApp);
    }

    @Bean
    public PushSender fcmPushSender(FirebaseMessaging firebaseMessaging) {
        log.info("Push notifications enabled (push.enabled=true), using FcmPushSender (FCM HTTP v1)");
        return new FcmPushSender(firebaseMessaging);
    }

    /**
     * Разрешает креды сервисного аккаунта по приоритету: инлайн-JSON → файл-путь → ADC.
     */
    private GoogleCredentials resolveCredentials(PushProperties.Fcm fcm) throws IOException {
        if (StringUtils.hasText(fcm.getCredentialsJson())) {
            log.info("Loading FCM credentials from inline JSON (push.fcm.credentials-json)");
            try (InputStream in = new ByteArrayInputStream(
                    fcm.getCredentialsJson().getBytes(StandardCharsets.UTF_8))) {
                return GoogleCredentials.fromStream(in);
            }
        }
        if (StringUtils.hasText(fcm.getCredentialsPath())) {
            log.info("Loading FCM credentials from file path (push.fcm.credentials-path)");
            try (InputStream in = Files.newInputStream(Path.of(fcm.getCredentialsPath()))) {
                return GoogleCredentials.fromStream(in);
            }
        }
        log.info("Loading FCM credentials from Application Default Credentials (ADC)");
        return GoogleCredentials.getApplicationDefault();
    }
}

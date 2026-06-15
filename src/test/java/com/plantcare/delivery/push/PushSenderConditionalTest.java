package com.plantcare.delivery.push;

import com.google.firebase.messaging.FirebaseMessaging;
import com.plantcare.core.config.NoopPushSender;
import com.plantcare.core.config.PushConfig;
import com.plantcare.core.service.PushSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Условная регистрация {@link PushSender} по тумблеру {@code push.enabled} (issue #176).
 *
 * <p>Проверяем оба конца тумблера:
 * <ul>
 *   <li>{@code push.enabled=false} (и отсутствие проперти) → активен {@link NoopPushSender}
 *       из {@link PushConfig}, Firebase не инициализируется;</li>
 *   <li>{@code push.enabled=true} → {@link FcmPushConfig} активируется и регистрирует
 *       {@link FcmPushSender}. Чтобы НЕ ходить в реальный Firebase, в контекст подложен
 *       мок {@link FirebaseMessaging}, а бин {@code fcmPushSender} собран из него.</li>
 * </ul>
 */
@DisplayName("Условная регистрация PushSender по push.enabled (issue #176)")
class PushSenderConditionalTest {

    @Test
    @DisplayName("push.enabled=false → активен NoopPushSender")
    void should_register_noop_when_push_disabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(PushConfig.class)
                .withPropertyValues("push.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(PushSender.class);
                    assertThat(context.getBean(PushSender.class)).isInstanceOf(NoopPushSender.class);
                });
    }

    @Test
    @DisplayName("проперти push.enabled отсутствует → matchIfMissing → NoopPushSender")
    void should_register_noop_when_push_property_missing() {
        new ApplicationContextRunner()
                .withUserConfiguration(PushConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(PushSender.class);
                    assertThat(context.getBean(PushSender.class)).isInstanceOf(NoopPushSender.class);
                });
    }

    @Test
    @DisplayName("push.enabled=true → NoopPushSender из core НЕ регистрируется")
    void should_not_register_noop_when_push_enabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(PushConfig.class)
                .withPropertyValues("push.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(NoopPushSender.class));
    }

    @Test
    @DisplayName("push.enabled=true → fcmPushSender собирает реальный FcmPushSender (без реального Firebase)")
    void should_register_fcm_sender_when_push_enabled() {
        // Прямая проверка фабрики бина с замоканным FirebaseMessaging: реальный Firebase
        // Admin SDK инициализируется только в firebaseApp(), который здесь не вызывается.
        FcmPushConfig config = new FcmPushConfig();
        FirebaseMessaging messaging = mock(FirebaseMessaging.class);

        PushSender sender = config.fcmPushSender(messaging);

        assertThat(sender).isInstanceOf(FcmPushSender.class);
    }

    @Test
    @DisplayName("FcmPushConfig помечен @ConditionalOnProperty(push.enabled=true)")
    void should_guard_fcm_config_behind_push_enabled_property() {
        ConditionalOnProperty annotation = FcmPushConfig.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).containsExactly("push.enabled");
        assertThat(annotation.havingValue()).isEqualTo("true");
    }
}

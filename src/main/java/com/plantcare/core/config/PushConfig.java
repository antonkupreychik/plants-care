package com.plantcare.core.config;

import com.plantcare.core.service.PushSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring-конфигурация push-канала (issue #175, ADR-014).
 *
 * <p>Регистрирует {@link PushSender}-бин в зависимости от {@code push.enabled}:
 * <ul>
 *   <li>{@code push.enabled=false} (дефолт, см. {@link #noopPushSender()}) → {@link NoopPushSender}
 *       — без внешних вызовов и без инициализации Firebase Admin SDK.</li>
 *   <li>{@code push.enabled=true} → реальный {@code FcmPushSender}, который регистрирует
 *       {@code com.plantcare.delivery.push.FcmPushConfig} (слой доставки, issue #176 / ADR-016).</li>
 * </ul>
 *
 * <p>Здесь, в {@code core}, остаётся ТОЛЬКО no-op-ветка: core знает про порт и заглушку,
 * но не про реальную FCM-реализацию — выбор {@code FcmPushSender} живёт в delivery-пакете.
 * Так держится ArchUnit-правило {@code core ∌ delivery}.
 */
@Configuration
@EnableConfigurationProperties(PushProperties.class)
@Slf4j
public class PushConfig {

    /**
     * No-op-отправитель: используется когда push выключен (дефолт). Реальную FCM-реализацию
     * при {@code push.enabled=true} регистрирует {@code com.plantcare.delivery.push.FcmPushConfig};
     * эти два бина взаимоисключающи по {@code push.enabled}.
     */
    @Bean
    @ConditionalOnProperty(name = "push.enabled", havingValue = "false", matchIfMissing = true)
    public PushSender noopPushSender() {
        log.info("Push notifications disabled (push.enabled=false), using NoopPushSender");
        return new NoopPushSender();
    }
}

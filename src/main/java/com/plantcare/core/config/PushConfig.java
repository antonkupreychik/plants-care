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
 *   <li>{@code push.enabled=false} (дефолт) → {@link NoopPushSender} — без внешних вызовов.</li>
 *   <li>{@code push.enabled=true} → реальный провайдер (будет добавлен отдельной задачей,
 *       сейчас также NoopPushSender — заглушка для будущей FCM / APNs-интеграции).</li>
 * </ul>
 *
 * <p>FCM / APNs-клиенты требуют доп. зависимостей в pom.xml и отдельного ADR —
 * реализуются отдельной задачей. Текущая задача (#175) ставит порт,
 * регистрирует тумблер и подключает шедулер к каналу.
 */
@Configuration
@EnableConfigurationProperties(PushProperties.class)
@Slf4j
public class PushConfig {

    /**
     * No-op-отправитель: используется когда push выключен.
     * Имеет наименьший приоритет — перекрывается реальными реализациями
     * через {@code @ConditionalOnProperty(havingValue = "true")}.
     */
    @Bean
    @ConditionalOnProperty(name = "push.enabled", havingValue = "false", matchIfMissing = true)
    public PushSender noopPushSender() {
        log.info("Push notifications disabled (push.enabled=false), using NoopPushSender");
        return new NoopPushSender();
    }

    /**
     * Заглушка для режима {@code push.enabled=true}: реальная FCM / APNs-интеграция
     * будет добавлена отдельной задачей. Логирует предупреждение, чтобы не потерять
     * включённый тумблер без реальной реализации.
     */
    @Bean
    @ConditionalOnProperty(name = "push.enabled", havingValue = "true")
    public PushSender enabledPushSender() {
        log.warn("push.enabled=true but real FCM/APNs provider is not yet implemented — " +
                "using NoopPushSender as stub. Implement FCM/APNs integration separately.");
        return new NoopPushSender();
    }
}

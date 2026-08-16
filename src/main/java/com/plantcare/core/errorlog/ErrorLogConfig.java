package com.plantcare.core.errorlog;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Включает {@link ErrorLogProperties} (issue #97). Остальные бины журнала ошибок —
 * обычные {@code @Repository}/{@code @Service}/{@code @Component}, их поднимает
 * компонент-скан.
 */
@Configuration
@EnableConfigurationProperties(ErrorLogProperties.class)
public class ErrorLogConfig {
}

package com.plantcare.core.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Issue #280, эпик #277 фаза 2: активирует {@link RateLimitProperties}
 * для распределённого rate-limit. {@link com.plantcare.core.config.RedisProperties}
 * активируется отдельно в {@code RedisConfig} (фаза 0) — здесь не дублируем.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {
}

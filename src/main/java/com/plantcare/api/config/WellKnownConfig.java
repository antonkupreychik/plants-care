package com.plantcare.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Регистрирует {@link DeepLinkProperties} как бин конфигурации.
 * Issue #215: Universal Links (iOS) / App Links (Android).
 */
@Configuration
@EnableConfigurationProperties(DeepLinkProperties.class)
public class WellKnownConfig {
}

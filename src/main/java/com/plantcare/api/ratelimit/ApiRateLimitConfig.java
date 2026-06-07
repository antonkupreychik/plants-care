package com.plantcare.api.ratelimit;

import com.plantcare.core.ratelimit.RateLimitEventService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Registers the {@link ApiRateLimitFilter} as a Spring bean (issue #92).
 *
 * <p>Declared as a {@code @Bean} here rather than as {@code @Component} directly
 * on the filter to control registration conditions and avoid duplicate filter
 * registration in slice tests that don't import this config.
 */
@Configuration
@EnableConfigurationProperties(ApiRateLimitProperties.class)
public class ApiRateLimitConfig {

    @Bean
    public OncePerRequestFilter apiRateLimitFilter(ApiRateLimitProperties props,
                                                    RateLimitEventService eventService) {
        return new ApiRateLimitFilter(props, eventService);
    }
}

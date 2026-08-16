package com.plantcare.core.observability;

import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Регистрация {@link MdcContextFilter} в сервлет-цепочке (issue #97).
 *
 * <p>Регистрируем именно {@link FilterRegistrationBean}, а не {@code @Component}-фильтр:
 * <ul>
 *   <li>нужен явный порядок — строго ПОСЛЕ цепочки Spring Security
 *       ({@link SecurityFilterProperties#DEFAULT_FILTER_ORDER} = -100), чтобы
 *       {@code SecurityContextHolder} уже был заполнен и в MDC попал реальный userId;</li>
 *   <li>один регистратор покрывает все цепочки сразу ({@code /api/**}, {@code /admin/**},
 *       default) — не пришлось трогать три разных {@code SecurityFilterChain}.</li>
 * </ul>
 */
@Configuration
public class MdcConfig {

    /** Смещение от цепочки Spring Security: чуть позже неё, но раньше всего прочего. */
    private static final int ORDER_AFTER_SECURITY = SecurityFilterProperties.DEFAULT_FILTER_ORDER + 10;

    @Bean
    public FilterRegistrationBean<MdcContextFilter> mdcContextFilterRegistration() {
        FilterRegistrationBean<MdcContextFilter> registration =
                new FilterRegistrationBean<>(new MdcContextFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(ORDER_AFTER_SECURITY);
        registration.setName("mdcContextFilter");
        return registration;
    }
}

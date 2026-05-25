package com.plantcare.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Issue #127 (фаза api): отдельная {@link SecurityFilterChain} для REST-слоя.
 *
 * <p>Доставка через REST изолирована от веб-админки: {@code /api/**} — это
 * stateless-канал (никаких сессий, никакого form-login и CSRF-токенов из форм).
 * Цепочка отделена {@code securityMatcher("/api/**")} и стоит раньше дефолтной
 * (она в {@link com.plantcare.admin.config.AdminSecurityConfig}); матчеры
 * {@code /admin/**} и {@code /actuator/prometheus} с ней не пересекаются.
 *
 * <p><b>Scope #127 — только каркас.</b> Реальная аутентификация (JWT / Apple /
 * Google) приедет в #88; до тех пор эндпоинты остаются открытыми
 * ({@code permitAll}), как и сейчас в дефолтной цепочке. Здесь фиксируется
 * именно граница и stateless-политика, чтобы #88 наращивал аутентификацию
 * в одном понятном месте, а не правил общий конфиг с if-ами.
 */
@Configuration
public class ApiSecurityConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .build();
    }
}

package com.plantcare.api.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * SecurityFilterChain для публичного REST API ({@code /api/v1/**}).
 *
 * <p>Issue #127 завёл каркас этой цепочки (stateless, отделена от веб-админки),
 * issue #88 наполнил её реальной аутентификацией: наш access-JWT
 * ({@code appJwtDecoder}, HS256), не Apple/Google JWKS (у тех отдельные
 * верификаторы в сервисах). STATELESS, без CSRF (мобильный клиент, bearer-токены).
 *
 * <p>Публичны только {@code /api/v1/auth/**}, справочники ({@code species},
 * {@code care-types}) и {@code /api/v1/health}; остальное требует аутентификации.
 *
 * <p>{@code @Order(0)} — выше дефолтной цепочки ({@code AdminSecurityConfig},
 * @Order 3), чтобы перехватывать {@code /api/v1/**} раньше permitAll-цепочки.
 * Admin form-login (@Order 1) и prometheus (@Order 2) не затрагиваются: у них
 * свой {@code securityMatcher}. Маршрут {@code /calendar/{token}.ics} не под
 * {@code /api/}, поэтому остаётся в дефолтной permitAll-цепочке.
 */
@Configuration
@EnableWebSecurity
public class ApiSecurityConfig {

    @Bean
    @Order(0)
    @ConditionalOnProperty(prefix = "plantcare.auth", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JwtDecoder appJwtDecoder)
            throws Exception {
        return http
                .securityMatcher("/api/v1/**")
                .authorizeHttpRequests(auth -> auth
                        // logout/logout-all требуют bearer-токен (issue #178) — должны
                        // идти ДО общего permitAll на /api/v1/auth/** (первое совпадение
                        // выигрывает).
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/logout", "/api/v1/auth/logout-all").authenticated()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/api/v1/species/**", "/api/v1/care-types/**").permitAll()
                        .requestMatchers("/api/v1/health").permitAll()
                        .anyRequest().authenticated()
                )
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(appJwtDecoder)))
                .build();
    }

    /**
     * Цепочка для ЛОКАЛЬНОЙ РАЗРАБОТКИ при {@code plantcare.auth.enabled=false}:
     * {@code /api/v1/**} открыты без аутентификации (токен не нужен). Текущего
     * пользователя контроллеры берут из {@code CurrentUserProvider} по
     * {@code plantcare.auth.dev-user-id}. НИКОГДА не включать на prod.
     *
     * <p>Не зависит от {@code appJwtDecoder} — ресурс-сервер не настраивается,
     * поэтому стартует даже без JWT-секрета.
     */
    @Bean
    @Order(0)
    @ConditionalOnProperty(prefix = "plantcare.auth", name = "enabled", havingValue = "false")
    public SecurityFilterChain apiSecurityFilterChainAuthDisabled(HttpSecurity http)
            throws Exception {
        return http
                .securityMatcher("/api/v1/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .build();
    }
}

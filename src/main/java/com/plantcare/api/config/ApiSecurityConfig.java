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
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * SecurityFilterChain для публичного REST API ({@code /api/v1/**}).
 *
 * <p>Issue #127 завёл каркас этой цепочки (stateless, отделена от веб-админки),
 * issue #88 наполнил её реальной аутентификацией: наш access-JWT
 * ({@code appJwtDecoder}, HS256), не Apple/Google JWKS (у тех отдельные
 * верификаторы в сервисах). STATELESS, без CSRF (мобильный клиент, bearer-токены).
 *
 * <p>Публичны только {@code /api/v1/auth/**}, справочники ({@code species},
 * {@code care-types}, {@code diseases}) и {@code /api/v1/health}; остальное требует аутентификации.
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
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                                       JwtDecoder appJwtDecoder,
                                                       OncePerRequestFilter apiRateLimitFilter,
                                                       CorsConfigurationSource corsConfigurationSource)
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
                        .requestMatchers("/api/v1/species/**", "/api/v1/care-types/**", "/api/v1/diseases/**").permitAll()
                        .requestMatchers("/api/v1/health").permitAll()
                        .anyRequest().authenticated()
                )
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(appJwtDecoder)))
                // Issue #92: rate limiting filter runs after JWT auth so we can identify users
                .addFilterAfter(apiRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                // Issue #92: security headers
                .headers(headers -> headers
                        .contentTypeOptions(contentType -> {})
                        .frameOptions(frameOptions -> frameOptions.deny())
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                )
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
    public SecurityFilterChain apiSecurityFilterChainAuthDisabled(HttpSecurity http,
                                                                   OncePerRequestFilter apiRateLimitFilter,
                                                                   CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        return http
                .securityMatcher("/api/v1/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                // Issue #92: rate limiting and security headers also in dev mode
                .addFilterAfter(apiRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .headers(headers -> headers
                        .contentTypeOptions(contentType -> {})
                        .frameOptions(frameOptions -> frameOptions.deny())
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                )
                .build();
    }

    /**
     * CORS configuration (issue #92).
     * <ul>
     *   <li>Dev: allow localhost:* (any port)
     *   <li>Prod: allow app.plantscare.ru
     * </ul>
     * Origins configurable via {@code CORS_ALLOWED_ORIGINS} env variable
     * (comma-separated). Defaults cover localhost dev and prod domain.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();
        String rawOrigins = System.getenv("CORS_ALLOWED_ORIGINS");
        if (rawOrigins != null && !rawOrigins.isBlank()) {
            for (String origin : rawOrigins.split(",")) {
                config.addAllowedOriginPattern(origin.trim());
            }
        } else {
            // localhost dev (any port), future prod domain
            config.addAllowedOriginPattern("http://localhost:[*]");
            config.addAllowedOriginPattern("https://localhost:[*]");
            config.addAllowedOrigin("https://app.plantscare.ru");
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}

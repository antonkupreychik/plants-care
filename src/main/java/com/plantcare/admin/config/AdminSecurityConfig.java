package com.plantcare.admin.config;

import com.plantcare.admin.handler.LoggingAuthenticationFailureHandler;
import com.plantcare.admin.handler.LoggingAuthenticationSuccessHandler;
import com.plantcare.admin.ratelimit.LoginRateLimitFilter;
import lombok.RequiredArgsConstructor;
import com.plantcare.admin.ratelimit.LoginRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Три SecurityFilterChain:
 *   1. @Order(1) /admin/** — form login, CSRF, session 8h, rate limiter
 *   2. @Order(2) /actuator/prometheus — HTTP Basic, ADMIN role (issue #115).
 *      Регистрируется только если admin enabled (есть креды). На dev без кредов
 *      падает в default chain (permitAll) — это осознанный компромисс, чтобы
 *      разработчик мог локально посмотреть метрики без настройки логина.
 *   3. @Order(3) всё остальное — permitAll, CSRF off (бот, /actuator/health и т.п.)
 *
 * Бины с form login создаются ТОЛЬКО при заданных env. Если их нет —
 * adminFilterChain не поднимается, /admin/** падает в default chain (permitAll),
 * и DisabledAdminController отдаёт 503.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AdminProperties.class)
@RequiredArgsConstructor
public class AdminSecurityConfig {

    public static final String ADMIN_ENABLED_EXPR =
            "!T(org.springframework.util.StringUtils).isEmpty('${admin.username:}') " +
            "and !T(org.springframework.util.StringUtils).isEmpty('${admin.password-bcrypt-hash:}')";

    @Bean
    @Order(1)
    @ConditionalOnExpression(ADMIN_ENABLED_EXPR)
    public SecurityFilterChain adminSecurityFilterChain(
            HttpSecurity http,
            LoginRateLimitFilter rateLimitFilter,
            LoggingAuthenticationSuccessHandler successHandler,
            LoggingAuthenticationFailureHandler failureHandler
    ) throws Exception {
        return http
                .securityMatcher("/admin/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/login").permitAll()
                        .anyRequest().hasRole("ADMIN")
                )
                .formLogin(form -> form
                        .loginPage("/admin/login")
                        .loginProcessingUrl("/admin/login")
                        .successHandler(successHandler)
                        .failureHandler(failureHandler)
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/admin/logout")
                        .logoutSuccessUrl("/admin/login")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                )
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * Issue #115: {@code /actuator/prometheus} закрыт HTTP Basic с ролью ADMIN.
     * Используем тот же {@link InMemoryUserDetailsManager} что и для /admin/**,
     * чтобы не плодить отдельных учёток для скрейпа метрик. Prometheus в
     * Railway/Grafana Cloud конфигурируется с теми же {@code ADMIN_USERNAME} /
     * пара-bcrypt-хешем, что и админ-панель.
     *
     * <p>CSRF выключаем — это GET-эндпоинт для machine-to-machine, никаких форм.
     * Session создавать тоже не нужно (STATELESS): scrape — это серия независимых
     * запросов.
     */
    @Bean
    @Order(2)
    @ConditionalOnExpression(ADMIN_ENABLED_EXPR)
    public SecurityFilterChain prometheusSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/actuator/prometheus")
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
                .httpBasic(org.springframework.security.config.Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(
                                org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .build();
    }

    /**
     * Default-цепочка для всего, что не /admin/**. В рамках Phase 0 (issue #84)
     * {@code /api/v1/**}, {@code /swagger-ui/**} и {@code /v3/api-docs/**} публичны:
     * аутентификация REST API появится в следующих фазах.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .build();
    }

    @Bean
    @ConditionalOnExpression(ADMIN_ENABLED_EXPR)
    public InMemoryUserDetailsManager adminUserDetailsManager(AdminProperties props) {
        UserDetails admin = User.withUsername(props.getUsername())
                .password("{bcrypt}" + props.getPasswordBcryptHash())
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(admin);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * LoginRateLimitFilter регистрируем как @Bean (а не через @Component на классе),
     * чтобы @WebMvcTest, который автоматически подхватывает все Filter-beans вне
     * зависимости от @ConditionalOn*, не пытался создать его в слайс-тестах
     * контроллеров, где AdminSecurityConfig не импортирован.
     */
    @Bean
    @ConditionalOnExpression(ADMIN_ENABLED_EXPR)
    public LoginRateLimitFilter loginRateLimitFilter(LoginRateLimiter loginRateLimiter) {
        return new LoginRateLimitFilter(loginRateLimiter);
    }
}

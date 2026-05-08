package com.plantcare.bot.admin.config;

import com.plantcare.bot.admin.handler.LoggingAuthenticationFailureHandler;
import com.plantcare.bot.admin.handler.LoggingAuthenticationSuccessHandler;
import com.plantcare.bot.admin.ratelimit.LoginRateLimitFilter;
import lombok.RequiredArgsConstructor;
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
 * Две SecurityFilterChain:
 *   1. @Order(1) /admin/** — form login, CSRF, session 8h, rate limiter
 *   2. @Order(2) всё остальное — permitAll, CSRF off (бот, /actuator/**)
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

    @Bean
    @Order(2)
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
}

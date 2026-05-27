package com.plantcare.api.config;

import com.plantcare.core.domain.User;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Интеграционный тест security-цепочки публичного API (issue #88, ADR-011).
 *
 * <p>Фильтры включены (НЕ addFilters=false): защищённые {@code /api/v1/**} без
 * Bearer-токена → 401; с валидным JWT (claim sub = users.id) — проходят.
 * Публичные {@code /api/v1/auth/**} доступны без токена.
 */
@AutoConfigureMockMvc
class ApiSecurityIT extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void cleanup() {
        userRepository.deleteAll();
    }

    @Test
    void should_return_401_when_protected_endpoint_called_without_bearer() throws Exception {
        // act + assert — /api/v1/today защищён, без токена → 401
        mockMvc.perform(get("/api/v1/today"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_pass_authentication_when_valid_jwt_present() throws Exception {
        // arrange — реальный пользователь, чей id попадёт в claim sub
        User user = userRepository.save(User.builder()
                .email("secured@example.com")
                .emailVerified(true)
                .build());

        // act + assert — с валидным JWT запрос проходит security и доходит до контроллера (200)
        mockMvc.perform(get("/api/v1/today")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk());
    }

    @Test
    void should_allow_public_auth_endpoint_without_bearer() throws Exception {
        // act + assert — /api/v1/auth/** публичны (permitAll). Невалидное тело → 400,
        // но не 401: значит security пропустил запрос дальше.
        mockMvc.perform(get("/api/v1/auth/email/request"))
                .andExpect(status().is(org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED.value()));
    }
}

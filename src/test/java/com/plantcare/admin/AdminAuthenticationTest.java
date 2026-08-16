package com.plantcare.admin;

import com.plantcare.admin.ratelimit.LoginRateLimiter;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("Admin authentication — form login flow")
class AdminAuthenticationTest extends IntegrationTestBase {

    // Credentials match application-test.yml (admin.username / admin.password-bcrypt-hash).
    // Using the same fixed values as all other admin tests ensures Spring reuses one cached
    // ApplicationContext — and therefore one TimeZoneEngine — across AdminAuthenticationTest,
    // AdminDashboardTest, AdminLayoutTest, AdminRateLimitTest, AdminUserDetailTest,
    // AdminUserListTest (issue #239).
    private static final String USERNAME = "admin";
    // Plain-text password whose bcrypt hash (cost 10) is stored in application-test.yml.
    private static final String PASSWORD = "test-admin-password";

    @Autowired private MockMvc mockMvc;
    @Autowired private LoginRateLimiter loginRateLimiter;

    /**
     * Сбрасываем login-rate-limiter перед каждым тестом. Раньше каждый admin-тест
     * поднимал свой контекст (разные креды → разный ключ кэша), поэтому имел свой
     * чистый лимитер. После консолидации контекстов (issue #239) лимитер общий с
     * {@code AdminRateLimitTest}, который специально исчерпывает 5 попыток с того же
     * IP (127.0.0.1, дефолтный remoteAddr MockMvc) — без сброса логин-флоу-тесты
     * ловили бы 429 вместо ожидаемых 401/302.
     */
    @BeforeEach
    void resetRateLimiter() {
        loginRateLimiter.reset("127.0.0.1");
    }

    @Test
    @DisplayName("GET /admin без авторизации → 302 на /admin/login")
    void unauthenticatedRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                // Spring Security 7 отдаёт относительный редирект (/admin/login) вместо
                // абсолютного (http://localhost/admin/login), как было в Security 6.
                // Паттерн '**/admin/login' на относительный путь не матчится — проверяем точно.
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    @DisplayName("GET /admin/login возвращает форму")
    void loginPageRendersForm() throws Exception {
        mockMvc.perform(get("/admin/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Plants Care")));
    }

    @Test
    @DisplayName("Успешный логин → 302 на /admin")
    void successfulLoginRedirects() throws Exception {
        mockMvc.perform(post("/admin/login")
                        .param("username", USERNAME).param("password", PASSWORD).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"));
    }

    @Test
    @DisplayName("Неверный пароль → 401")
    void wrongPasswordReturns401() throws Exception {
        mockMvc.perform(post("/admin/login")
                        .param("username", USERNAME).param("password", "wrong").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("/actuator/health публичный")
    void actuatorRemainsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}

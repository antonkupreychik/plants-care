package com.plantcare.bot.admin;

import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("Admin authentication — form login flow")
class AdminAuthenticationTest extends IntegrationTestBase {

    private static final String USERNAME = "admin";
    private static final String PASSWORD = "test-password-123";
    private static final String BCRYPT_HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);

    @DynamicPropertySource
    static void adminProps(DynamicPropertyRegistry registry) {
        registry.add("admin.username", () -> USERNAME);
        registry.add("admin.password-bcrypt-hash", () -> BCRYPT_HASH);
    }

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("GET /admin без авторизации → 302 на /admin/login")
    void unauthenticatedRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/admin/login"));
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

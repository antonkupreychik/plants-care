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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("Admin login rate limit — 5/мин")
class AdminRateLimitTest extends IntegrationTestBase {

    private static final String USERNAME = "admin";
    private static final String BCRYPT_HASH =
            new BCryptPasswordEncoder(12).encode("correct-password");

    @DynamicPropertySource
    static void adminProps(DynamicPropertyRegistry registry) {
        registry.add("admin.username", () -> USERNAME);
        registry.add("admin.password-bcrypt-hash", () -> BCRYPT_HASH);
    }

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("6-я попытка за минуту → 429")
    void sixthAttemptIsRateLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/admin/login")
                            .param("username", USERNAME)
                            .param("password", "wrong-" + i)
                            .with(csrf()))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/admin/login")
                        .param("username", USERNAME)
                        .param("password", "wrong-final")
                        .with(csrf()))
                .andExpect(status().isTooManyRequests());
    }
}

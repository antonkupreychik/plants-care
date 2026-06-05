package com.plantcare.admin;

import com.plantcare.admin.ratelimit.LoginRateLimiter;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("Admin login rate limit — 5/мин")
class AdminRateLimitTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private LoginRateLimiter loginRateLimiter;

    /**
     * Стартуем с чистого лимитера: контекст общий с другими admin-тестами (issue #239),
     * поэтому неудачные логины из соседних тестов могли бы заполнить счётчик 127.0.0.1
     * и сломать ассерты на первые 5 попыток (401).
     */
    @BeforeEach
    void resetRateLimiter() {
        loginRateLimiter.reset("127.0.0.1");
    }

    @Test
    @DisplayName("6-я попытка за минуту → 429")
    void sixthAttemptIsRateLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/admin/login")
                            .param("username", "admin")
                            .param("password", "wrong-" + i)
                            .with(csrf()))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/admin/login")
                        .param("username", "admin")
                        .param("password", "wrong-final")
                        .with(csrf()))
                .andExpect(status().isTooManyRequests());
    }
}

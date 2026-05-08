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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("Admin layout — рендеринг базового шаблона")
class AdminLayoutTest extends IntegrationTestBase {

    private static final String BCRYPT_HASH =
            new BCryptPasswordEncoder(12).encode("anything");

    @DynamicPropertySource
    static void adminProps(DynamicPropertyRegistry registry) {
        registry.add("admin.username", () -> "admin");
        registry.add("admin.password-bcrypt-hash", () -> BCRYPT_HASH);
    }

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("/admin под admin → layout с шапкой, сайдбаром и именем юзера")
    void dashboardRendersFullLayout() throws Exception {
        mockMvc.perform(get("/admin").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                // навигация
                .andExpect(content().string(containsString("Dashboard")))
                .andExpect(content().string(containsString("Users")))
                .andExpect(content().string(containsString("Species")))
                // имя юзера в шапке через sec:authentication
                .andExpect(content().string(containsString("admin")))
                // CSRF метатеги для HTMX
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("name=\"_csrf_header\"")))
                // CDN ссылки подключились
                .andExpect(content().string(containsString("daisyui@4")))
                .andExpect(content().string(containsString("htmx.org")));
    }
}

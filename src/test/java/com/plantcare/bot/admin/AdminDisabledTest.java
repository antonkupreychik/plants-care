package com.plantcare.bot.admin;

import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("Admin disabled — env-переменные не заданы")
class AdminDisabledTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void adminProps(DynamicPropertyRegistry registry) {
        registry.add("admin.username", () -> "");
        registry.add("admin.password-bcrypt-hash", () -> "");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /admin → 503 с телом «Admin panel is disabled»")
    void adminReturns503() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string("Admin panel is disabled"));
    }

    @Test
    @DisplayName("Любой /admin/* path → 503")
    void anyAdminPathReturns503() throws Exception {
        mockMvc.perform(get("/admin/login")).andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/admin/users")).andExpect(status().isServiceUnavailable());
    }
}

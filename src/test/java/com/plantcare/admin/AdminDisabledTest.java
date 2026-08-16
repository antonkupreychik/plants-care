package com.plantcare.admin;

import com.plantcare.bot.support.IntegrationTestBase;
import net.iakovlev.timeshape.TimeZoneEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("Admin disabled — env-переменные не заданы")
class AdminDisabledTest extends IntegrationTestBase {

    // Admin is disabled in this test (empty username/password-hash), which forces a separate
    // Spring context. Mock the TimeZoneEngine so its heavy geo-dataset is NOT loaded here —
    // the real engine is tested in AwaitingTimezoneStateHandlerTest.
    @MockitoBean
    TimeZoneEngine timeZoneEngine;

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

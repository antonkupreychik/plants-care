package com.plantcare.admin;

import com.plantcare.admin.dashboard.AdminDashboardService;
import com.plantcare.admin.dashboard.dto.DashboardDto;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("Admin Dashboard — метрики и Stuck/Recent блоки")
class AdminDashboardTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AdminDashboardService dashboardService;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.execute("DELETE FROM care_history");
        jdbc.execute("DELETE FROM notifications_log");
        jdbc.execute("DELETE FROM care_schedules");
        jdbc.execute("DELETE FROM plants");
        jdbc.execute("DELETE FROM users");
    }

    @Test
    @DisplayName("Пустая БД → все нули, страница рендерится")
    void emptyDatabaseRenders() throws Exception {
        mockMvc.perform(get("/admin").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Активных юзеров")))
                .andExpect(content().string(containsString("Растений")))
                .andExpect(content().string(containsString("Шедулер")));
    }

    @Test
    @DisplayName("Активные юзеры — считают только !blocked и !paused")
    void countsActiveUsersCorrectly() {
        jdbc.update("INSERT INTO users (telegram_chat_id, username, is_blocked) VALUES (?,?,?)",
                100L, "active1", false);
        jdbc.update("INSERT INTO users (telegram_chat_id, username, is_blocked) VALUES (?,?,?)",
                101L, "active2", false);
        jdbc.update("INSERT INTO users (telegram_chat_id, is_blocked) VALUES (?,?)",
                102L, true);  // blocked
        jdbc.update("INSERT INTO users (telegram_chat_id, paused_until) VALUES (?, now() + interval '1 day')",
                103L);  // paused

        DashboardDto dto = dashboardService.loadDashboard();

        assertThat(dto.activeUsers()).isEqualTo(2);
    }

}

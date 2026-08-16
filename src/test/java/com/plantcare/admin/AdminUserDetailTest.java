package com.plantcare.admin;

import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("Admin user detail — read-only данные")
class AdminUserDetailTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;
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
    @DisplayName("Несуществующий юзер → 404")
    void notFound() throws Exception {
        mockMvc.perform(get("/admin/users/999999").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Юзер без растений и истории — placeholders")
    void emptyUserRenders() throws Exception {
        Long uid = jdbc.queryForObject(
                "INSERT INTO users (telegram_chat_id, username) VALUES (?, ?) RETURNING id",
                Long.class, 100L, "blank_user");

        mockMvc.perform(get("/admin/users/{id}", uid).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("blank_user")))
                .andExpect(content().string(containsString("Растений нет")))
                .andExpect(content().string(containsString("История пустая")));
    }

}

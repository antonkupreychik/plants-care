package com.plantcare.admin;

import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("Admin Users list — фильтры, поиск, пагинация")
class AdminUserListTest extends IntegrationTestBase {

    private static final String BCRYPT_HASH = new BCryptPasswordEncoder(12).encode("anything");

    @DynamicPropertySource
    static void adminProps(DynamicPropertyRegistry registry) {
        registry.add("admin.username", () -> "admin");
        registry.add("admin.password-bcrypt-hash", () -> BCRYPT_HASH);
    }

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
    @DisplayName("Без auth → 302 на login")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/admin/users")).andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("Пустая БД — placeholder")
    void emptyRenders() throws Exception {
        mockMvc.perform(get("/admin/users").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Нет юзеров")));
    }

    @Test
    @DisplayName("Фильтр blocked показывает только заблокированных")
    void blockedFilter() throws Exception {
        jdbc.update("INSERT INTO users (telegram_chat_id, username, is_blocked) VALUES (?,?,?)",
                100L, "active1", false);
        jdbc.update("INSERT INTO users (telegram_chat_id, username, is_blocked) VALUES (?,?,?)",
                101L, "blockedguy", true);

        mockMvc.perform(get("/admin/users").param("filter", "blocked")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("blockedguy")))
                .andExpect(content().string(not(containsString("active1"))));
    }

    @Test
    @DisplayName("Search по username")
    void searchByUsername() throws Exception {
        jdbc.update("INSERT INTO users (telegram_chat_id, username) VALUES (?,?)", 200L, "alice");
        jdbc.update("INSERT INTO users (telegram_chat_id, username) VALUES (?,?)", 201L, "bob");

        mockMvc.perform(get("/admin/users").param("search", "alice")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("alice")))
                .andExpect(content().string(not(containsString("bob"))));
    }

    @Test
    @DisplayName("Search по chat_id")
    void searchByChatId() throws Exception {
        jdbc.update("INSERT INTO users (telegram_chat_id, username) VALUES (?,?)", 12345L, "alice");
        jdbc.update("INSERT INTO users (telegram_chat_id, username) VALUES (?,?)", 67890L, "bob");

        mockMvc.perform(get("/admin/users").param("search", "12345")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("alice")))
                .andExpect(content().string(not(containsString("bob"))));
    }

}

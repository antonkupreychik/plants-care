/*
package com.plantcare.bot.admin;

import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("Admin user actions — мутации")
class AdminUserActionsTest extends IntegrationTestBase {

    private static final String BCRYPT_HASH = new BCryptPasswordEncoder(12).encode("anything");

    @DynamicPropertySource
    static void adminProps(DynamicPropertyRegistry registry) {
        registry.add("admin.username", () -> "admin");
        registry.add("admin.password-bcrypt-hash", () -> BCRYPT_HASH);
        registry.add("telegram.bot.token", () -> "test-token");
    }

    @MockitoBean(name = "adminTelegramClient")
    private TelegramClient telegramClient;

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

    private long createUser(long chatId, String username) {
        return jdbc.queryForObject(
                "INSERT INTO users (telegram_chat_id, username) VALUES (?, ?) RETURNING id",
                Long.class, chatId, username);
    }

    @Test
    @DisplayName("POST /reset-state переводит state в IDLE")
    void resetState() throws Exception {
        long uid = createUser(100L, "stuck");
        jdbc.update("UPDATE users SET conversation_state = 'AWAITING_PLANT_NAME' WHERE id = ?", uid);

        mockMvc.perform(post("/admin/users/{id}/reset-state", uid)
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        String state = jdbc.queryForObject(
                "SELECT conversation_state FROM users WHERE id = ?", String.class, uid);
        assertThat(state).isEqualTo("IDLE");
    }

    @Test
    @DisplayName("POST /toggle-block переключает is_blocked")
    void toggleBlock() throws Exception {
        long uid = createUser(101L, "victim");

        mockMvc.perform(post("/admin/users/{id}/toggle-block", uid)
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Boolean blocked = jdbc.queryForObject(
                "SELECT is_blocked FROM users WHERE id = ?", Boolean.class, uid);
        assertThat(blocked).isTrue();

        mockMvc.perform(post("/admin/users/{id}/toggle-block", uid)
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        blocked = jdbc.queryForObject(
                "SELECT is_blocked FROM users WHERE id = ?", Boolean.class, uid);
        assertThat(blocked).isFalse();
    }

    @Test
    @DisplayName("POST /pause days=7 ставит paused_until в будущее")
    void pauseByDays() throws Exception {
        long uid = createUser(102L, "vacation");

        mockMvc.perform(post("/admin/users/{id}/pause", uid)
                        .param("days", "7")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Timestamp t = jdbc.queryForObject(
                "SELECT paused_until FROM users WHERE id = ?", Timestamp.class, uid);
        assertThat(t).isNotNull();
        assertThat(t.toInstant()).isAfter(Instant.now().plus(Duration.ofDays(6)));
    }

    @Test
    @DisplayName("POST /unpause обнуляет paused_until")
    void unpause() throws Exception {
        long uid = createUser(103L, "back_at_work");
        jdbc.update("UPDATE users SET paused_until = now() + interval '5 days' WHERE id = ?", uid);

        mockMvc.perform(post("/admin/users/{id}/unpause", uid)
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Timestamp t = jdbc.queryForObject(
                "SELECT paused_until FROM users WHERE id = ?", Timestamp.class, uid);
        assertThat(t).isNull();
    }

    @Test
    @DisplayName("POST /send-message вызывает TelegramClient.execute")
    void sendMessage() throws Exception {
        long uid = createUser(200L, "recipient");

        mockMvc.perform(post("/admin/users/{id}/send-message", uid)
                        .param("text", "Привет, тебе нужно полить монстеру")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        verify(telegramClient).execute(ArgumentMatchers.any(SendMessage.class));
    }
}
*/

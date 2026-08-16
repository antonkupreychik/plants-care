package com.plantcare.admin;

import com.plantcare.api.auth.service.AuthService;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Секция «Аутентификация» и «История привязок» на странице юзера (issue #93).
 */
@AutoConfigureMockMvc
@DisplayName("Admin user auth — провайдеры, привязки, история")
class AdminUserAuthTest extends IntegrationTestBase {

    private static final String APPLE_SUBJECT = "001234.abcdef0123456789abcdef.5678";
    private static final String GOOGLE_SUBJECT = "108765432109876543210";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuthService authService;

    @AfterEach
    void cleanup() {
        jdbc.execute("DELETE FROM magic_link_tokens");
        jdbc.execute("DELETE FROM care_history");
        jdbc.execute("DELETE FROM notifications_log");
        jdbc.execute("DELETE FROM care_schedules");
        jdbc.execute("DELETE FROM plants");
        jdbc.execute("DELETE FROM users");
    }

    private long createUser(Long chatId, String email, String appleSubject, String googleSubject) {
        return jdbc.queryForObject("""
                        INSERT INTO users (telegram_chat_id, username, email, email_verified,
                                           apple_subject, google_subject)
                        VALUES (?, 'auth_user', ?, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class, chatId, email, email != null, appleSubject, googleSubject);
    }

    // ===== Отображение секции =====

    @Test
    @DisplayName("should_render_all_four_providers_with_masked_identifiers_when_user_has_them")
    void should_render_all_four_providers_with_masked_identifiers_when_user_has_them() throws Exception {
        long uid = createUser(750123456789L, "alexander@example.com", APPLE_SUBJECT, GOOGLE_SUBJECT);

        mockMvc.perform(get("/admin/users/{id}", uid).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Аутентификация")))
                .andExpect(content().string(containsString("Telegram")))
                .andExpect(content().string(containsString("Apple")))
                .andExpect(content().string(containsString("Google")))
                // маскированные варианты присутствуют
                .andExpect(content().string(containsString("al***@example.com")))
                .andExpect(content().string(containsString("0012***5678")))
                // а полные идентификаторы — нет
                .andExpect(content().string(not(containsString("alexander@example.com"))))
                .andExpect(content().string(not(containsString(APPLE_SUBJECT))))
                .andExpect(content().string(not(containsString(GOOGLE_SUBJECT))));
    }

    @Test
    @DisplayName("should_warn_when_user_has_no_linked_providers")
    void should_warn_when_user_has_no_linked_providers() throws Exception {
        long uid = createUser(null, null, null, null);

        mockMvc.perform(get("/admin/users/{id}", uid).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Не привязан ни один провайдер")));
    }

    // ===== Разрыв привязки =====

    @Test
    @DisplayName("should_login_via_remaining_provider_when_one_of_two_is_unlinked")
    void should_login_via_remaining_provider_when_one_of_two_is_unlinked() throws Exception {
        long uid = createUser(null, "two@example.com", null, GOOGLE_SUBJECT);

        mockMvc.perform(post("/admin/users/{id}/auth/unlink", uid)
                        .param("provider", "GOOGLE")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flash"))
                // предупреждения «залогиниться не сможет» быть не должно —
                // остался ещё один провайдер
                .andExpect(flash().attributeCount(1));

        String google = jdbc.queryForObject(
                "SELECT google_subject FROM users WHERE id = ?", String.class, uid);
        String email = jdbc.queryForObject(
                "SELECT email FROM users WHERE id = ?", String.class, uid);
        assertThat(google).isNull();
        assertThat(email).isEqualTo("two@example.com");

        // Оставшийся провайдер по-прежнему резолвит того же юзера — это и есть
        // «залогиниться через оставшийся» (тот же путь, что у magic link).
        var resolved = authService.findOrCreateUser(
                "two@example.com", true, AuthService.Provider.EMAIL, null);
        assertThat(resolved.getId()).isEqualTo(uid);
    }

    @Test
    @DisplayName("should_revoke_refresh_token_epoch_when_provider_is_unlinked")
    void should_revoke_refresh_token_epoch_when_provider_is_unlinked() throws Exception {
        long uid = createUser(null, "epoch@example.com", APPLE_SUBJECT, null);

        mockMvc.perform(post("/admin/users/{id}/auth/unlink", uid)
                        .param("provider", "APPLE")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Timestamp epoch = jdbc.queryForObject(
                "SELECT tokens_valid_from FROM users WHERE id = ?", Timestamp.class, uid);
        assertThat(epoch).isNotNull();
    }

    @Test
    @DisplayName("should_warn_when_unlinking_the_last_provider")
    void should_warn_when_unlinking_the_last_provider() throws Exception {
        long uid = createUser(750123456789L, null, null, null);

        mockMvc.perform(post("/admin/users/{id}/auth/unlink", uid)
                        .param("provider", "TELEGRAM")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashError"));

        Long chatId = jdbc.queryForObject(
                "SELECT telegram_chat_id FROM users WHERE id = ?", Long.class, uid);
        assertThat(chatId).isNull();
    }

    @Test
    @DisplayName("should_reject_unlink_when_provider_is_not_linked")
    void should_reject_unlink_when_provider_is_not_linked() throws Exception {
        long uid = createUser(750123456789L, null, null, null);

        mockMvc.perform(post("/admin/users/{id}/auth/unlink", uid)
                        .param("provider", "APPLE")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashError"));

        Long chatId = jdbc.queryForObject(
                "SELECT telegram_chat_id FROM users WHERE id = ?", Long.class, uid);
        assertThat(chatId).isEqualTo(750123456789L);
    }

    @Test
    @DisplayName("should_return_404_when_unlinking_for_unknown_user")
    void should_return_404_when_unlinking_for_unknown_user() throws Exception {
        mockMvc.perform(post("/admin/users/{id}/auth/unlink", 999999L)
                        .param("provider", "APPLE")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ===== История привязок =====

    @Test
    @DisplayName("should_derive_link_history_actions_from_magic_link_tokens")
    void should_derive_link_history_actions_from_magic_link_tokens() throws Exception {
        long uid = createUser(null, "history@example.com", null, null);
        insertToken("history@example.com", "hash-claimed", "now() + interval '10 minutes'", "now()");
        insertToken("history@example.com", "hash-expired", "now() - interval '1 hour'", null);
        insertToken("history@example.com", "hash-live", "now() + interval '10 minutes'", null);

        mockMvc.perform(get("/admin/users/{id}/auth/link-history", uid)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("CLAIMED")))
                .andExpect(content().string(containsString("EXPIRED")))
                .andExpect(content().string(containsString("GENERATED")));
    }

    @Test
    @DisplayName("should_filter_link_history_by_date_when_range_is_given")
    void should_filter_link_history_by_date_when_range_is_given() throws Exception {
        long uid = createUser(null, "filter@example.com", null, null);
        insertToken("filter@example.com", "hash-old", "now() + interval '10 minutes'", "now()");
        jdbc.update("UPDATE magic_link_tokens SET created_at = TIMESTAMPTZ '2020-01-01 10:00:00+00' "
                + "WHERE token_hash = 'hash-old'");

        mockMvc.perform(get("/admin/users/{id}/auth/link-history", uid)
                        .param("from", "2020-01-01").param("to", "2020-01-01")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("CLAIMED")));

        mockMvc.perform(get("/admin/users/{id}/auth/link-history", uid)
                        .param("from", "2020-01-02")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("За выбранный период привязок не было")));
    }

    @Test
    @DisplayName("should_tolerate_blank_and_broken_date_filters")
    void should_tolerate_blank_and_broken_date_filters() throws Exception {
        long uid = createUser(null, "blank@example.com", null, null);

        mockMvc.perform(get("/admin/users/{id}/auth/link-history", uid)
                        .param("from", "").param("to", "not-a-date")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("should_explain_missing_history_when_user_has_no_email")
    void should_explain_missing_history_when_user_has_no_email() throws Exception {
        long uid = createUser(750123456789L, null, null, null);

        mockMvc.perform(get("/admin/users/{id}/auth/link-history", uid)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("не привязан email")));
    }

    private void insertToken(String email, String hash, String expiresExpr, String consumedExpr) {
        jdbc.update("INSERT INTO magic_link_tokens (email, token_hash, expires_at, consumed_at) "
                + "VALUES (?, ?, " + expiresExpr + ", " + (consumedExpr == null ? "NULL" : consumedExpr) + ")",
                email, hash);
    }
}

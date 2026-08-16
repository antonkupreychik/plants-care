package com.plantcare.admin.audit;

import com.plantcare.admin.dto.SpeciesFormDto;
import com.plantcare.admin.service.AdminSpeciesService;
import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.domain.Species;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #98, ключевой AC: выполняем реальные админские действия и проверяем,
 * что каждое попало в аудит с корректными {@code action}, {@code target} и
 * {@code details}. Это тест на проводку, а не на страницу.
 */
@AutoConfigureMockMvc
@DisplayName("Admin audit — деструктивные действия попадают в лог")
class AdminAuditWiringTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AdminSpeciesService speciesService;

    @BeforeEach
    void clean() {
        AuditTestSupport.clear(jdbc);
    }

    @AfterEach
    void cleanup() {
        AuditTestSupport.clear(jdbc);
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

    private List<Map<String, Object>> auditRows() {
        return jdbc.queryForList("SELECT * FROM admin_audit_log ORDER BY id");
    }

    private Map<String, Object> singleRow() {
        List<Map<String, Object>> rows = auditRows();
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    @Test
    @DisplayName("toggle-block пишет USER_TOGGLE_BLOCK с before/after")
    void toggleBlockAudited() throws Exception {
        long uid = createUser(9001L, "victim");

        mockMvc.perform(post("/admin/users/{id}/toggle-block", uid)
                        .with(user("chief").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Map<String, Object> row = singleRow();
        assertThat(row.get("action")).isEqualTo("USER_TOGGLE_BLOCK");
        assertThat(row.get("admin_username")).isEqualTo("chief");
        assertThat(row.get("target_type")).isEqualTo("USER");
        assertThat(row.get("target_id")).isEqualTo(String.valueOf(uid));
        assertThat(String.valueOf(row.get("details")))
                .contains("\"before\": false")
                .contains("\"after\": true");
        assertThat(row.get("request_ip")).isNotNull();
    }

    @Test
    @DisplayName("pause пишет USER_PAUSE со сроком паузы")
    void pauseAudited() throws Exception {
        long uid = createUser(9002L, "vacation");

        mockMvc.perform(post("/admin/users/{id}/pause", uid).param("days", "7")
                        .with(user("chief").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Map<String, Object> row = singleRow();
        assertThat(row.get("action")).isEqualTo("USER_PAUSE");
        assertThat(String.valueOf(row.get("details"))).contains("pausedUntil");
    }

    @Test
    @DisplayName("unpause пишет USER_UNPAUSE без details")
    void unpauseAudited() throws Exception {
        long uid = createUser(9003L, "back");

        mockMvc.perform(post("/admin/users/{id}/unpause", uid)
                        .with(user("chief").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Map<String, Object> row = singleRow();
        assertThat(row.get("action")).isEqualTo("USER_UNPAUSE");
        assertThat(row.get("details")).isNull();
    }

    @Test
    @DisplayName("reset-state пишет USER_RESET_STATE")
    void resetStateAudited() throws Exception {
        long uid = createUser(9004L, "stuck");
        jdbc.update("UPDATE users SET conversation_state = 'AWAITING_PLANT_NAME' WHERE id = ?", uid);

        mockMvc.perform(post("/admin/users/{id}/reset-state", uid)
                        .with(user("chief").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(singleRow().get("action")).isEqualTo("USER_RESET_STATE");
    }

    @Test
    @DisplayName("Установка и снятие флага пишут SET и CLEAR")
    void flagChangesAudited() throws Exception {
        long uid = createUser(9005L, "beta_tester");

        mockMvc.perform(post("/admin/users/{id}/flags/set", uid).param("code", "beta_ui")
                        .with(user("chief").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/admin/users/{id}/flags/clear", uid).param("code", "beta_ui")
                        .with(user("chief").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Map<String, Object>> rows = auditRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("action")).isEqualTo("USER_FLAG_SET");
        assertThat(String.valueOf(rows.get(0).get("details"))).contains("beta_ui");
        assertThat(rows.get(1).get("action")).isEqualTo("USER_FLAG_CLEAR");
    }

    @Test
    @DisplayName("Повторная установка того же флага второй записи не плодит")
    void unchangedFlagIsNotAudited() throws Exception {
        long uid = createUser(9006L, "idempotent");

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/admin/users/{id}/flags/set", uid).param("code", "beta_ui")
                            .with(user("chief").roles("ADMIN")).with(csrf()))
                    .andExpect(status().is3xxRedirection());
        }

        assertThat(auditRows()).hasSize(1);
    }

    @Test
    @DisplayName("CRUD вида пишет CREATE, UPDATE и DELETE с именем")
    void speciesLifecycleAudited() {
        SpeciesFormDto form = new SpeciesFormDto();
        form.setName("Тестовая монстера");
        form.setLatinName("Monstera testis");
        Species created = speciesService.create(form, "botanist");

        form.setName("Переименованная монстера");
        speciesService.update(created.getId(), form, "botanist");

        speciesService.delete(created.getId(), "botanist");

        List<Map<String, Object>> rows = auditRows();
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> r.get("action"))
                .containsExactly("SPECIES_CREATE", "SPECIES_UPDATE", "SPECIES_DELETE");
        assertThat(rows).allSatisfy(r ->
                assertThat(r.get("target_type")).isEqualTo("SPECIES"));
        assertThat(String.valueOf(rows.get(1).get("details")))
                .contains("Тестовая монстера")
                .contains("Переименованная монстера");
        assertThat(String.valueOf(rows.get(2).get("details"))).contains("linkedPlants");
    }

    @Test
    @DisplayName("Inline-патч поля вида пишет before/after")
    void speciesPatchAudited() {
        SpeciesFormDto form = new SpeciesFormDto();
        form.setName("Фикус для патча");
        form.setWateringDays(7);
        Species created = speciesService.create(form, "botanist");

        speciesService.patchField(created.getId(), "wateringDays", "14", "botanist");
        speciesService.delete(created.getId(), "botanist");

        List<Map<String, Object>> rows = auditRows();
        assertThat(rows.get(1).get("action")).isEqualTo("SPECIES_UPDATE");
        assertThat(String.valueOf(rows.get(1).get("details")))
                .contains("wateringDays")
                .contains("\"before\": \"7\"")
                .contains("\"after\": \"14\"");
    }

    @Test
    @DisplayName("Запись аудита не мешает самому действию примениться")
    void auditDoesNotBreakAction() throws Exception {
        long uid = createUser(9007L, "normal");

        mockMvc.perform(post("/admin/users/{id}/toggle-block", uid)
                        .with(user("chief").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Boolean blocked = jdbc.queryForObject(
                "SELECT is_blocked FROM users WHERE id = ?", Boolean.class, uid);
        assertThat(blocked).isTrue();
        assertThat(auditRows()).hasSize(1);
    }
}

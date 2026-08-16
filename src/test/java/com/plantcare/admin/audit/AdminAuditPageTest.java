package com.plantcare.admin.audit;

import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #98: страница /admin/audit — фильтры, пагинация, CSV-экспорт.
 *
 * <p>Строки таблицы опознаём по {@code target_id}, а не по коду действия:
 * выпадашка фильтра рендерит ВСЕ значения {@code AdminAuditAction}, поэтому
 * имя действия есть в HTML всегда, независимо от того, что попало в выборку.
 */
@AutoConfigureMockMvc
@DisplayName("Admin audit — страница, фильтры и экспорт")
class AdminAuditPageTest extends IntegrationTestBase {

    /** Маркеры строк: уникальные target_id, которых нет больше нигде в разметке. */
    private static final String USER_ROW = "#1101";
    private static final String SPECIES_ROW = "#7707";
    private static final String BROADCAST_ROW = "#5005";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        AuditTestSupport.clear(jdbc);

        insert("alice", "USER_TOGGLE_BLOCK", "USER", "1101",
                "{\"before\":false,\"after\":true}", Instant.now().minus(1, ChronoUnit.DAYS));
        insert("bob", "SPECIES_DELETE", "SPECIES", "7707",
                "{\"name\":\"Монстера\"}", Instant.now().minus(10, ChronoUnit.DAYS));
        insert("alice", "BROADCAST_SENT", "BROADCAST", "5005",
                "{\"audienceSize\":42}", Instant.now().minus(2, ChronoUnit.HOURS));
    }

    private void insert(String admin, String action, String targetType, String targetId,
                        String details, Instant when) {
        jdbc.update("""
                INSERT INTO admin_audit_log
                    (occurred_at, admin_username, action, target_type, target_id, details, request_ip)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), '127.0.0.1')
                """, Timestamp.from(when), admin, action, targetType, targetId, details);
    }

    @Test
    @DisplayName("Без auth → редирект на login")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/admin/audit")).andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("Без фильтров показывает все записи")
    void listsEverything() throws Exception {
        mockMvc.perform(get("/admin/audit").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(USER_ROW)))
                .andExpect(content().string(containsString(SPECIES_ROW)))
                .andExpect(content().string(containsString(BROADCAST_ROW)));
    }

    @Test
    @DisplayName("Фильтр по админу отсекает чужие записи")
    void filtersByAdmin() throws Exception {
        mockMvc.perform(get("/admin/audit").param("admin", "bob")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(SPECIES_ROW)))
                .andExpect(content().string(not(containsString(USER_ROW))))
                .andExpect(content().string(not(containsString(BROADCAST_ROW))));
    }

    @Test
    @DisplayName("Фильтр по action оставляет только его")
    void filtersByAction() throws Exception {
        mockMvc.perform(get("/admin/audit").param("action", "BROADCAST_SENT")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(BROADCAST_ROW)))
                .andExpect(content().string(not(containsString(SPECIES_ROW))));
    }

    @Test
    @DisplayName("Фильтр по target_type оставляет только его")
    void filtersByTargetType() throws Exception {
        mockMvc.perform(get("/admin/audit").param("targetType", "SPECIES")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(SPECIES_ROW)))
                .andExpect(content().string(not(containsString(BROADCAST_ROW))));
    }

    @Test
    @DisplayName("Диапазон дат отсекает записи старше границы")
    void filtersByDateRange() throws Exception {
        String from = LocalDate.now(ZoneOffset.UTC).minusDays(3).toString();

        mockMvc.perform(get("/admin/audit").param("from", from)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(BROADCAST_ROW)))
                .andExpect(content().string(containsString(USER_ROW)))
                .andExpect(content().string(not(containsString(SPECIES_ROW))));
    }

    @Test
    @DisplayName("Верхняя граница включает выбранный день целиком")
    void upperBoundIsInclusive() throws Exception {
        String today = LocalDate.now(ZoneOffset.UTC).toString();

        mockMvc.perform(get("/admin/audit").param("to", today)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(BROADCAST_ROW)));
    }

    @Test
    @DisplayName("Невалидный action не роняет страницу, а трактуется как «все»")
    void unknownActionIsIgnored() throws Exception {
        mockMvc.perform(get("/admin/audit").param("action", "DROP_TABLE")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(SPECIES_ROW)))
                .andExpect(content().string(containsString(BROADCAST_ROW)));
    }

    @Test
    @DisplayName("Пустой лог рендерит плейсхолдер")
    void emptyRendersPlaceholder() throws Exception {
        AuditTestSupport.clear(jdbc);

        mockMvc.perform(get("/admin/audit").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Записей нет")));
    }

    @Test
    @DisplayName("CSV-экспорт отдаёт attachment с шапкой и строками")
    void exportsCsv() throws Exception {
        mockMvc.perform(get("/admin/audit/export.csv").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().string(containsString("id,occurred_at_utc,admin,action")))
                .andExpect(content().string(containsString("\"alice\",\"BROADCAST_SENT\"")))
                .andExpect(content().string(containsString("\"bob\",\"SPECIES_DELETE\"")));
    }

    @Test
    @DisplayName("CSV-экспорт уважает те же фильтры, что и страница")
    void exportRespectsFilters() throws Exception {
        mockMvc.perform(get("/admin/audit/export.csv").param("admin", "bob")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("SPECIES_DELETE")))
                .andExpect(content().string(not(containsString("BROADCAST_SENT"))));
    }

    @Test
    @DisplayName("JSON с кавычками переживает CSV-экранирование")
    void csvEscapesJsonQuotes() throws Exception {
        mockMvc.perform(get("/admin/audit/export.csv").param("action", "SPECIES_DELETE")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"\"name\"\"")));
    }
}

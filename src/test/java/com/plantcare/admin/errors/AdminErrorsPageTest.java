package com.plantcare.admin.errors;

import com.plantcare.admin.errors.dto.ErrorLogFilter;
import com.plantcare.admin.errors.service.AdminErrorsService;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Страница {@code /admin/errors} (issue #97): топ-10 с группировкой, комбинируемые
 * фильтры, деталка и «события юзера за час до».
 *
 * <p>Аппендер здесь выключен ({@code plants.error-log.enabled=false}): строки в
 * {@code error_logs} кладутся тестом напрямую, а фоновый перехват логов добавлял бы в
 * таблицу посторонние записи и делал ассерты недетерминированными. Сам перехват
 * проверяется отдельно — {@code ErrorLogPipelineTest}.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "plants.error-log.enabled=false")
@DisplayName("Admin /admin/errors — топ-10, фильтры, деталка (#97)")
class AdminErrorsPageTest extends IntegrationTestBase {

    private static final String NPE = "java.lang.NullPointerException";
    private static final String TIMEOUT = "java.net.SocketTimeoutException";
    private static final String NPE_FINGERPRINT = NPE + " at com.plantcare.bot.Svc.run(Svc.java:10)";
    private static final String TIMEOUT_FINGERPRINT = TIMEOUT + " at com.plantcare.core.Weather.get(Weather.java:20)";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AdminErrorsService service;

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM error_logs");

        Instant now = Instant.now();
        // Три одинаковые по сути NPE — обязаны схлопнуться в одну группу.
        insert(now.minus(10, ChronoUnit.MINUTES), "ERROR", "com.plantcare.bot.Svc",
                "plant 1 failed", NPE, NPE_FINGERPRINT, 100L, "/api/v1/plants/1");
        insert(now.minus(20, ChronoUnit.MINUTES), "ERROR", "com.plantcare.bot.Svc",
                "plant 2 failed", NPE, NPE_FINGERPRINT, 100L, "/api/v1/plants/2");
        insert(now.minus(30, ChronoUnit.MINUTES), "ERROR", "com.plantcare.bot.Svc",
                "plant 3 failed", NPE, NPE_FINGERPRINT, 200L, "/api/v1/plants/3");
        // Одиночный WARN из другого пакета и без юзера.
        insert(now.minus(40, ChronoUnit.MINUTES), "WARN", "com.plantcare.core.Weather",
                "weather provider timeout", TIMEOUT, TIMEOUT_FINGERPRINT, null, null);
        // Запись вне окна топ-24h — в топ попасть не должна.
        insert(now.minus(3, ChronoUnit.DAYS), "ERROR", "com.plantcare.bot.Old",
                "ancient failure", NPE, "java.lang.NullPointerException at com.plantcare.bot.Old.x(Old.java:1)",
                300L, "/api/v1/old");
    }

    @Test
    @DisplayName("should_collapse_identical_errors_when_building_top_groups")
    void should_collapse_identical_errors_when_building_top_groups() {
        var groups = service.topGroups();

        assertThat(groups).hasSize(2);
        var top = groups.getFirst();
        assertThat(top.fingerprint()).isEqualTo(NPE_FINGERPRINT);
        assertThat(top.occurrences()).isEqualTo(3);
        assertThat(top.affectedUsers()).isEqualTo(2);
        assertThat(top.sampleMessage()).isEqualTo("plant 1 failed");
        // Запись трёхдневной давности осталась за окном топ-24h.
        assertThat(groups).noneMatch(g -> g.fingerprint().contains("Old.x"));
    }

    @Test
    @DisplayName("should_render_page_with_top_and_list_when_admin_opens_it")
    void should_render_page_with_top_and_list_when_admin_opens_it() throws Exception {
        mockMvc.perform(get("/admin/errors").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Топ-10")))
                .andExpect(content().string(containsString("weather provider timeout")))
                .andExpect(content().string(containsString("plant 1 failed")));
    }

    @Test
    @DisplayName("should_filter_by_level_when_level_is_given")
    void should_filter_by_level_when_level_is_given() {
        var page = service.page(new ErrorLogFilter(null, null, null, null, null, "WARN", null), 1);

        assertThat(page.totalItems()).isEqualTo(1);
        assertThat(page.items()).singleElement()
                .satisfies(item -> assertThat(item.message()).isEqualTo("weather provider timeout"));
    }

    @Test
    @DisplayName("should_combine_filters_when_user_logger_and_message_are_given")
    void should_combine_filters_when_user_logger_and_message_are_given() {
        var page = service.page(
                new ErrorLogFilter(null, null, 100L, "com.plantcare.bot", "plant 2", null, null), 1);

        assertThat(page.totalItems()).isEqualTo(1);
        assertThat(page.items().getFirst().message()).isEqualTo("plant 2 failed");
    }

    @Test
    @DisplayName("should_return_nothing_when_combined_filters_contradict")
    void should_return_nothing_when_combined_filters_contradict() {
        var page = service.page(
                new ErrorLogFilter(null, null, 200L, "com.plantcare.core", null, null, null), 1);

        assertThat(page.totalItems()).isZero();
        assertThat(page.items()).isEmpty();
    }

    @Test
    @DisplayName("should_filter_by_fingerprint_when_group_is_selected_from_top")
    void should_filter_by_fingerprint_when_group_is_selected_from_top() {
        var page = service.page(
                new ErrorLogFilter(null, null, null, null, null, null, NPE_FINGERPRINT), 1);

        assertThat(page.totalItems()).isEqualTo(3);
    }

    @Test
    @DisplayName("should_exclude_old_rows_when_date_range_is_narrowed")
    void should_exclude_old_rows_when_date_range_is_narrowed() {
        var today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        var page = service.page(new ErrorLogFilter(today, today, null, null, null, null, null), 1);

        assertThat(page.totalItems()).isLessThanOrEqualTo(4);
        assertThat(page.items()).noneMatch(item -> item.message().equals("ancient failure"));
    }

    @Test
    @DisplayName("should_search_case_insensitively_when_message_substring_is_given")
    void should_search_case_insensitively_when_message_substring_is_given() {
        var page = service.page(new ErrorLogFilter(null, null, null, null, "TIMEOUT", null, null), 1);

        assertThat(page.totalItems()).isEqualTo(1);
    }

    @Test
    @DisplayName("should_show_stack_trace_and_context_when_detail_is_opened")
    void should_show_stack_trace_and_context_when_detail_is_opened() throws Exception {
        long id = idOfMessage("plant 1 failed");

        mockMvc.perform(get("/admin/errors/{id}", id).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("plant 1 failed")))
                .andExpect(content().string(containsString("at com.plantcare.bot.Svc.run")))
                .andExpect(content().string(containsString("corr-plant 1 failed")));
    }

    @Test
    @DisplayName("should_return_404_when_detail_id_is_unknown")
    void should_return_404_when_detail_id_is_unknown() throws Exception {
        mockMvc.perform(get("/admin/errors/{id}", 999_999L).with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("should_list_user_events_in_hour_before_when_context_is_requested")
    void should_list_user_events_in_hour_before_when_context_is_requested() throws Exception {
        long id = idOfMessage("plant 1 failed");

        mockMvc.perform(get("/admin/errors/{id}/user-context", id).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                // Юзер 100 — обе его ошибки в пределах часа.
                .andExpect(content().string(containsString("plant 1 failed")))
                .andExpect(content().string(containsString("plant 2 failed")))
                // Юзер 200 в контекст не входит.
                .andExpect(content().string(not(containsString("plant 3 failed"))));
    }

    @Test
    @DisplayName("should_return_empty_context_when_error_has_no_user")
    void should_return_empty_context_when_error_has_no_user() {
        long id = idOfMessage("weather provider timeout");

        assertThat(service.userContext(id)).isEmpty();
    }

    @Test
    @DisplayName("should_return_empty_context_when_error_is_already_purged")
    void should_return_empty_context_when_error_is_already_purged() {
        assertThat(service.userContext(999_999L)).isEmpty();
    }

    private long idOfMessage(String message) {
        return jdbc.queryForObject(
                "SELECT id FROM error_logs WHERE message = ?", Long.class, message);
    }

    private void insert(Instant at, String level, String logger, String message,
                        String exceptionClass, String fingerprint, Long userId, String path) {
        jdbc.update("""
                        INSERT INTO error_logs (created_at, level, logger_name, message, exception_class,
                                                fingerprint, stack_trace, user_id, request_path,
                                                correlation_id, thread_name)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                Timestamp.from(at), level, logger, message, exceptionClass, fingerprint,
                exceptionClass + ": " + message + "\n\tat " + fingerprint.substring(fingerprint.indexOf(" at ") + 4),
                userId, path, "corr-" + message, "test-thread");
    }
}

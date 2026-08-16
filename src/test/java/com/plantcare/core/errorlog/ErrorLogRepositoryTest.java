package com.plantcare.core.errorlog;

import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Батч-инсерт и retention журнала ошибок против настоящего Postgres (issue #97):
 * юнит-тесты выше работают с моком репозитория, а тут проверяется сам SQL и то,
 * что запись переживает удалённого юзера (FK на users намеренно нет).
 *
 * <p>Аппендер выключен, чтобы фоновый перехват логов не подкладывал лишних строк —
 * набор аннотаций совпадает с {@code AdminErrorsPageTest}, поэтому контекст переиспользуется.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "plants.error-log.enabled=false")
@DisplayName("ErrorLogRepository — батч-инсерт и retention в Postgres (#97)")
class ErrorLogRepositoryTest extends IntegrationTestBase {

    @Autowired private ErrorLogRepository repository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM error_logs");
    }

    @Test
    @DisplayName("should_persist_all_columns_when_batch_is_inserted")
    void should_persist_all_columns_when_batch_is_inserted() {
        Instant at = Instant.parse("2026-05-20T10:00:00Z");
        repository.insertBatch(List.of(
                new ErrorLogEntry(at, "ERROR", "com.plantcare.bot.Svc", "failed",
                        "java.lang.RuntimeException", "fp-1", "stack here", 55L,
                        "/api/v1/plants", "corr-a", "http-1"),
                new ErrorLogEntry(at, "WARN", "com.plantcare.core.Weather", "slow",
                        null, "fp-2", null, null, null, null, "scheduler-1")));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM error_logs ORDER BY level");

        assertThat(rows).hasSize(2);
        Map<String, Object> error = rows.getFirst();
        assertThat(error.get("logger_name")).isEqualTo("com.plantcare.bot.Svc");
        assertThat(error.get("user_id")).isEqualTo(55L);
        assertThat(error.get("correlation_id")).isEqualTo("corr-a");
        assertThat(error.get("stack_trace")).isEqualTo("stack here");

        Map<String, Object> warn = rows.get(1);
        assertThat(warn.get("user_id")).isNull();
        assertThat(warn.get("exception_class")).isNull();
        assertThat(warn.get("request_path")).isNull();
    }

    @Test
    @DisplayName("should_do_nothing_when_batch_is_empty")
    void should_do_nothing_when_batch_is_empty() {
        repository.insertBatch(List.of());

        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("should_delete_only_old_rows_when_retention_runs")
    void should_delete_only_old_rows_when_retention_runs() {
        Instant now = Instant.now();
        repository.insertBatch(List.of(
                entry(now.minus(40, ChronoUnit.DAYS), "old"),
                entry(now.minus(31, ChronoUnit.DAYS), "also-old"),
                entry(now.minus(29, ChronoUnit.DAYS), "fresh"),
                entry(now, "newest")));

        int deleted = repository.deleteOlderThan(now.minus(30, ChronoUnit.DAYS));

        assertThat(deleted).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT message FROM error_logs", String.class))
                .containsExactlyInAnyOrder("fresh", "newest");
    }

    @Test
    @DisplayName("should_delete_nothing_when_all_rows_are_fresh")
    void should_delete_nothing_when_all_rows_are_fresh() {
        repository.insertBatch(List.of(entry(Instant.now(), "fresh")));

        assertThat(repository.deleteOlderThan(Instant.now().minus(30, ChronoUnit.DAYS))).isZero();
        assertThat(count()).isEqualTo(1);
    }

    /**
     * FK на {@code users} осознанно нет: журнал пишется из фонового потока и не должен
     * падать из-за id несуществующего юзера (напр. sub из токена удалённого аккаунта).
     */
    @Test
    @DisplayName("should_persist_row_when_user_id_points_to_missing_user")
    void should_persist_row_when_user_id_points_to_missing_user() {
        repository.insertBatch(List.of(new ErrorLogEntry(Instant.now(), "ERROR", "l", "orphan",
                null, "fp", null, 987_654_321L, null, null, "t")));

        assertThat(count()).isEqualTo(1);
    }

    private ErrorLogEntry entry(Instant at, String message) {
        return new ErrorLogEntry(at, "ERROR", "com.plantcare.bot.Svc", message,
                null, "fp", null, null, null, null, "t");
    }

    private long count() {
        Long total = jdbc.queryForObject("SELECT count(*) FROM error_logs", Long.class);
        return total == null ? 0L : total;
    }
}

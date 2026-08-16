package com.plantcare.admin.errors.repository;

import com.plantcare.admin.errors.dto.ErrorGroupDto;
import com.plantcare.admin.errors.dto.ErrorLogDetailDto;
import com.plantcare.admin.errors.dto.ErrorLogFilter;
import com.plantcare.admin.errors.dto.ErrorLogItemDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Чтение журнала ошибок для {@code /admin/errors} (issue #97).
 *
 * <p>{@link NamedParameterJdbcTemplate} — как у остальных admin-репозиториев
 * ({@code AdminUserListRepository}): фильтры комбинируются динамически, а строить под
 * это JPA-Specification над таблицей, у которой и entity-то нет, смысла нет.
 *
 * <p>Все пользовательские значения идут ТОЛЬКО через именованные параметры; в SQL руками
 * не подставляется ничего, кроме собранных здесь же кусков {@code AND ...} без данных.
 */
@Repository
@RequiredArgsConstructor
public class AdminErrorLogRepository {

    private final NamedParameterJdbcTemplate jdbc;

    /** Количество строк под фильтр — для пагинации. */
    @Transactional(readOnly = true)
    public long count(ErrorLogFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = buildWhere(filter, params);
        Long total = jdbc.queryForObject("SELECT count(*) FROM error_logs " + where, params, Long.class);
        return total == null ? 0L : total;
    }

    /** Страница списка, свежие сверху. */
    @Transactional(readOnly = true)
    public List<ErrorLogItemDto> find(ErrorLogFilter filter, int page, int pageSize) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = buildWhere(filter, params);
        params.addValue("limit", pageSize);
        params.addValue("offset", (long) (page - 1) * pageSize);

        String sql = """
                SELECT id, created_at, level, logger_name, message, exception_class,
                       user_id, request_path, correlation_id
                FROM error_logs
                """ + where + """

                ORDER BY created_at DESC, id DESC
                LIMIT :limit OFFSET :offset
                """;
        return jdbc.query(sql, params, AdminErrorLogRepository::mapItem);
    }

    /**
     * Топ уникальных ошибок за окно: группировка по {@code fingerprint}, самая частая
     * сверху. {@code sample_message} берётся у самой свежей ошибки группы через
     * {@code DISTINCT ON} в подзапросе — {@code max(message)} дал бы лексикографический
     * максимум, а не свежий пример.
     */
    @Transactional(readOnly = true)
    public List<ErrorGroupDto> findTopGroups(Instant since, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("since", Timestamp.from(since))
                .addValue("limit", limit);

        String sql = """
                WITH windowed AS (
                    SELECT fingerprint, exception_class, message, user_id, created_at
                    FROM error_logs
                    WHERE created_at >= :since
                ),
                grouped AS (
                    SELECT fingerprint,
                           count(*)                       AS occurrences,
                           count(DISTINCT user_id)        AS affected_users,
                           max(created_at)                AS last_seen
                    FROM windowed
                    GROUP BY fingerprint
                    ORDER BY occurrences DESC, last_seen DESC
                    LIMIT :limit
                ),
                samples AS (
                    SELECT DISTINCT ON (fingerprint) fingerprint, exception_class, message
                    FROM windowed
                    ORDER BY fingerprint, created_at DESC
                )
                SELECT g.fingerprint, s.exception_class, s.message AS sample_message,
                       g.occurrences, g.affected_users, g.last_seen
                FROM grouped g
                JOIN samples s ON s.fingerprint = g.fingerprint
                ORDER BY g.occurrences DESC, g.last_seen DESC
                """;

        return jdbc.query(sql, params, (rs, n) -> new ErrorGroupDto(
                rs.getString("fingerprint"),
                rs.getString("exception_class"),
                rs.getString("sample_message"),
                rs.getLong("occurrences"),
                rs.getLong("affected_users"),
                rs.getTimestamp("last_seen").toInstant()));
    }

    /** Полная запись для деталки, либо {@code null}, если её уже вычистил retention. */
    @Transactional(readOnly = true)
    public ErrorLogDetailDto findById(long id) {
        List<ErrorLogDetailDto> found = jdbc.query("""
                SELECT id, created_at, level, logger_name, message, exception_class,
                       fingerprint, stack_trace, user_id, request_path, correlation_id, thread_name
                FROM error_logs
                WHERE id = :id
                """,
                new MapSqlParameterSource("id", id),
                (rs, n) -> new ErrorLogDetailDto(
                        rs.getLong("id"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("level"),
                        rs.getString("logger_name"),
                        rs.getString("message"),
                        rs.getString("exception_class"),
                        rs.getString("fingerprint"),
                        rs.getString("stack_trace"),
                        rs.getObject("user_id", Long.class),
                        rs.getString("request_path"),
                        rs.getString("correlation_id"),
                        rs.getString("thread_name")));
        return found.isEmpty() ? null : found.getFirst();
    }

    /**
     * «Все события юзера за час до ошибки» (issue #97): все записи журнала этого юзера в
     * окне {@code [момент - window, момент]}, свежие сверху. Сама ошибка тоже входит в
     * выборку — так видно, что было непосредственно перед ней и чем закончилось.
     */
    @Transactional(readOnly = true)
    public List<ErrorLogItemDto> findUserContext(long userId, Instant until, java.time.Duration window, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("from", Timestamp.from(until.minus(window)))
                .addValue("to", Timestamp.from(until))
                .addValue("limit", limit);

        return jdbc.query("""
                SELECT id, created_at, level, logger_name, message, exception_class,
                       user_id, request_path, correlation_id
                FROM error_logs
                WHERE user_id = :userId
                  AND created_at >= :from
                  AND created_at <= :to
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """, params, AdminErrorLogRepository::mapItem);
    }

    /**
     * Собирает {@code WHERE} из непустых полей фильтра. Возвращает готовый кусок SQL,
     * значения складывает в {@code params}.
     */
    private static String buildWhere(ErrorLogFilter filter, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder("WHERE 1=1");

        Instant from = filter.fromInstant();
        if (from != null) {
            where.append(" AND created_at >= :from");
            params.addValue("from", Timestamp.from(from));
        }
        Instant to = filter.toInstantExclusive();
        if (to != null) {
            where.append(" AND created_at < :to");
            params.addValue("to", Timestamp.from(to));
        }
        if (filter.userId() != null) {
            where.append(" AND user_id = :userId");
            params.addValue("userId", filter.userId());
        }
        if (isNotBlank(filter.logger())) {
            // Префиксный матч: 'com.plantcare.bot' ловит весь пакет.
            where.append(" AND logger_name LIKE :logger");
            params.addValue("logger", filter.logger().trim() + "%");
        }
        if (isNotBlank(filter.message())) {
            where.append(" AND message ILIKE :message");
            params.addValue("message", "%" + filter.message().trim() + "%");
        }
        if (isNotBlank(filter.level())) {
            where.append(" AND level = :level");
            params.addValue("level", filter.level().trim().toUpperCase());
        }
        if (isNotBlank(filter.fingerprint())) {
            where.append(" AND fingerprint = :fingerprint");
            params.addValue("fingerprint", filter.fingerprint());
        }
        return where.toString();
    }

    private static ErrorLogItemDto mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new ErrorLogItemDto(
                rs.getLong("id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("level"),
                rs.getString("logger_name"),
                rs.getString("message"),
                rs.getString("exception_class"),
                rs.getObject("user_id", Long.class),
                rs.getString("request_path"),
                rs.getString("correlation_id"));
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}

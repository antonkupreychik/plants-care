package com.plantcare.admin.audit.repository;

import com.plantcare.admin.audit.dto.AdminAuditEntryDto;
import com.plantcare.admin.audit.dto.AdminAuditFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Доступ к append-only таблице {@code admin_audit_log} (issue #98).
 *
 * <p>Намеренно на голом JDBC, а не через JPA-entity — по образцу
 * {@code RateLimitEventRepository}:
 * <ul>
 *   <li>у записи нет жизненного цикла: только INSERT и SELECT, а
 *       dirty-checking Hibernate на такой таблице способен выпустить UPDATE,
 *       который триггер БД отвергнет уже в проде;</li>
 *   <li>фильтры страницы динамические — WHERE собирается по месту;</li>
 *   <li>{@code details} кладём готовой JSON-строкой с явным {@code ::jsonb}.</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class AdminAuditLogRepository {

    /** Потолок выгрузки в CSV — чтобы экспорт не съел heap на большой истории. */
    public static final int EXPORT_LIMIT = 10_000;

    private static final RowMapper<AdminAuditEntryDto> ROW_MAPPER = (rs, n) -> new AdminAuditEntryDto(
            rs.getLong("id"),
            rs.getTimestamp("occurred_at").toInstant(),
            rs.getString("admin_username"),
            rs.getString("action"),
            rs.getString("target_type"),
            rs.getString("target_id"),
            rs.getString("details"),
            rs.getString("request_ip"));

    private final JdbcTemplate jdbc;

    /**
     * Пишет запись аудита в отдельной транзакции: аудит не должен пропасть
     * из-за отката бизнес-транзакции и не должен её ронять.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(String action, String adminUsername, String targetType,
                       String targetId, String detailsJson, String requestIp) {
        jdbc.update("""
                INSERT INTO admin_audit_log
                    (occurred_at, admin_username, action, target_type, target_id, details, request_ip)
                VALUES (now(), ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, adminUsername, action, targetType, targetId, detailsJson, requestIp);
    }

    @Transactional(readOnly = true)
    public List<AdminAuditEntryDto> search(AdminAuditFilter filter, int limit, long offset) {
        var args = new ArrayList<>();
        String where = buildWhere(filter, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query(
                "SELECT * FROM admin_audit_log " + where + " ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, args.toArray());
    }

    @Transactional(readOnly = true)
    public long count(AdminAuditFilter filter) {
        var args = new ArrayList<>();
        String where = buildWhere(filter, args);
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_log " + where, Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    /** Список админов, встречающихся в логе — наполняет выпадашку фильтра. */
    @Transactional(readOnly = true)
    public List<String> distinctAdmins() {
        return jdbc.queryForList(
                "SELECT DISTINCT admin_username FROM admin_audit_log ORDER BY admin_username",
                String.class);
    }

    /** Типы объектов, встречающиеся в логе — наполняет выпадашку фильтра. */
    @Transactional(readOnly = true)
    public List<String> distinctTargetTypes() {
        return jdbc.queryForList(
                "SELECT DISTINCT target_type FROM admin_audit_log "
                        + "WHERE target_type IS NOT NULL ORDER BY target_type",
                String.class);
    }

    /**
     * Собирает WHERE из непустых полей фильтра, докладывая значения в
     * {@code args} в том же порядке. Имена колонок — литералы, всё
     * пользовательское уходит только в placeholder'ы.
     */
    private static String buildWhere(AdminAuditFilter filter, List<Object> args) {
        List<String> conditions = new ArrayList<>();
        if (filter.adminUsername() != null) {
            conditions.add("admin_username = ?");
            args.add(filter.adminUsername());
        }
        if (filter.action() != null) {
            conditions.add("action = ?");
            args.add(filter.action().name());
        }
        if (filter.targetType() != null) {
            conditions.add("target_type = ?");
            args.add(filter.targetType());
        }
        if (filter.targetId() != null) {
            conditions.add("target_id = ?");
            args.add(filter.targetId());
        }
        addBound(conditions, args, "occurred_at >= ?", filter.from());
        addBound(conditions, args, "occurred_at < ?", filter.to());
        return conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
    }

    private static void addBound(List<String> conditions, List<Object> args,
                                 String condition, Instant value) {
        if (value != null) {
            conditions.add(condition);
            args.add(Timestamp.from(value));
        }
    }
}

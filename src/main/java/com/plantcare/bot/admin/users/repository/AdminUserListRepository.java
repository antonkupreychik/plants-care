package com.plantcare.bot.admin.users.repository;

import com.plantcare.bot.admin.users.dto.UserListItemDto;
import com.plantcare.bot.admin.users.dto.UserListPageDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class AdminUserListRepository {

    private static final int PAGE_SIZE = 50;

    /** Whitelist для ORDER BY — защита от SQL injection. */
    private static final Map<String, String> SORT_WHITELIST = Map.of(
            "last_action", "last_action_at",
            "chat_id", "u.telegram_chat_id",
            "username", "u.username",
            "timezone", "u.timezone",
            "plants_count", "plants_count"
    );

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public UserListPageDto find(String filter, String search, String sort, String direction, int page) {
        int validPage = Math.max(1, page);
        String sortColumn = SORT_WHITELIST.getOrDefault(sort, "last_action_at");
        String dir = "asc".equalsIgnoreCase(direction) ? "ASC NULLS LAST" : "DESC NULLS LAST";
        String resolvedFilter = (filter == null || filter.isBlank()) ? "all" : filter;
        String resolvedSort = SORT_WHITELIST.containsKey(sort) ? sort : "last_action";
        String resolvedDir = "asc".equalsIgnoreCase(direction) ? "asc" : "desc";

        StringBuilder where = new StringBuilder("WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();

        switch (resolvedFilter) {
            case "active" -> where.append(" AND u.is_blocked = false " +
                    "AND (u.paused_until IS NULL OR u.paused_until <= now())");
            case "blocked" -> where.append(" AND u.is_blocked = true");
            case "paused" -> where.append(" AND u.paused_until > now()");
            case "stuck" -> where.append(" AND u.conversation_state != 'IDLE' " +
                    "AND u.updated_at < now() - interval '24 hours'");
            default -> { /* all */ }
        }

        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(COALESCE(u.username, '')) LIKE :search " +
                    "OR CAST(u.telegram_chat_id AS TEXT) LIKE :search)");
            params.addValue("search", "%" + search.toLowerCase().trim() + "%");
        }

        Long total = jdbc.queryForObject("SELECT count(*) FROM users u " + where, params, Long.class);
        long totalItems = total != null ? total : 0L;

        params.addValue("limit", PAGE_SIZE);
        params.addValue("offset", (validPage - 1) * PAGE_SIZE);

        String sql = """
                SELECT u.id, u.telegram_chat_id, u.username, u.timezone,
                       u.is_blocked, u.paused_until, u.conversation_state, u.updated_at,
                       COUNT(DISTINCT p.id) FILTER (WHERE p.archived_at IS NULL) AS plants_count,
                       MAX(ch.done_at) AS last_action_at
                FROM users u
                LEFT JOIN plants p ON p.user_id = u.id
                LEFT JOIN care_history ch ON ch.plant_id = p.id
                """ + where + """
               \s
                GROUP BY u.id
                ORDER BY\s""" + sortColumn + " " + dir + """
                
                LIMIT :limit OFFSET :offset
                """;

        List<UserListItemDto> items = jdbc.query(sql, params, this::mapRow);
        int totalPages = totalItems == 0 ? 1 : (int) Math.ceil((double) totalItems / PAGE_SIZE);

        return new UserListPageDto(items, validPage, PAGE_SIZE, totalItems, totalPages,
                resolvedFilter, search == null ? "" : search, resolvedSort, resolvedDir);
    }

    private UserListItemDto mapRow(ResultSet rs, int n) throws SQLException {
        Timestamp pausedUntil = rs.getTimestamp("paused_until");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        Timestamp lastActionAt = rs.getTimestamp("last_action_at");
        String state = rs.getString("conversation_state");
        boolean blocked = rs.getBoolean("is_blocked");
        boolean paused = pausedUntil != null && pausedUntil.toInstant().isAfter(Instant.now());
        boolean stuck = !"IDLE".equals(state) && updatedAt != null
                && updatedAt.toInstant().isBefore(Instant.now().minus(Duration.ofHours(24)));

        return new UserListItemDto(
                rs.getLong("id"),
                rs.getLong("telegram_chat_id"),
                rs.getString("username"),
                rs.getString("timezone"),
                rs.getLong("plants_count"),
                lastActionAt != null ? lastActionAt.toInstant() : null,
                blocked, paused, stuck, state
        );
    }
}

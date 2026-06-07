package com.plantcare.core.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Persists rate-limit 429 events for admin monitoring (issue #92).
 * Uses plain JDBC to avoid coupling with the JPA session.
 */
@Repository
@RequiredArgsConstructor
public class RateLimitEventRepository {

    private final JdbcTemplate jdbc;

    /**
     * Inserts a rate-limit event. Runs in a new transaction so the insert
     * does not interfere with any surrounding request transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(String ip, Long userId, String endpoint, String limitType) {
        jdbc.update("""
                INSERT INTO rate_limit_events (occurred_at, ip, user_id, endpoint, limit_type)
                VALUES (now(), ?, ?, ?, ?)
                """, ip, userId, endpoint, limitType);
    }

    /**
     * Returns top violators (by count) within the last {@code hours} hours.
     */
    @Transactional(readOnly = true)
    public List<TopViolatorDto> findTopViolatorsLastHours(int hours, int limit) {
        Timestamp since = Timestamp.from(Instant.now().minus(hours, ChronoUnit.HOURS));
        return jdbc.query("""
                SELECT ip,
                       user_id,
                       count(*) AS event_count,
                       max(occurred_at) AS last_seen
                FROM rate_limit_events
                WHERE occurred_at >= ?
                GROUP BY ip, user_id
                ORDER BY event_count DESC
                LIMIT ?
                """, (rs, n) -> new TopViolatorDto(
                        rs.getString("ip"),
                        rs.getObject("user_id", Long.class),
                        rs.getLong("event_count"),
                        rs.getTimestamp("last_seen").toInstant()),
                since, limit);
    }

    /**
     * Returns the total event count within the last {@code hours} hours.
     */
    @Transactional(readOnly = true)
    public long countLastHours(int hours) {
        Timestamp since = Timestamp.from(Instant.now().minus(hours, ChronoUnit.HOURS));
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM rate_limit_events WHERE occurred_at >= ?",
                Long.class, since);
        return count != null ? count : 0L;
    }
}

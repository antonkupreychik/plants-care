package com.plantcare.bot.admin.dashboard;

import com.plantcare.bot.admin.dashboard.dto.RecentCareActionDto;
import com.plantcare.bot.admin.dashboard.dto.StuckUserDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Чистый JdbcTemplate-репозиторий для админ-дашборда.
 * Намеренно не использует JPA — admin queries не пересекаются с продуктовой моделью,
 * проще читать и оптимизировать SQL.
 *
 * Схема: care_history и notifications_log НЕ содержат user_id — юзер выводится через
 * plants.user_id. Это значит для всех агрегатов по юзерам нужен JOIN с plants.
 */
@Repository
@RequiredArgsConstructor
public class AdminDashboardRepository {

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public long countActiveUsers() {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM users
                WHERE is_blocked = false
                  AND (paused_until IS NULL OR paused_until <= now())
                """, Long.class);
        return count != null ? count : 0L;
    }

    @Transactional(readOnly = true)
    public long countNonArchivedPlants() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM plants WHERE archived_at IS NULL", Long.class);
        return count != null ? count : 0L;
    }

    @Transactional(readOnly = true)
    public long countCareActionsToday(ZoneId zone) {
        LocalDate today = LocalDate.now(zone);
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime startOfNextDay = today.plusDays(1).atStartOfDay();

        Timestamp from = Timestamp.from(startOfDay.atZone(zone).toInstant());
        Timestamp to = Timestamp.from(startOfNextDay.atZone(zone).toInstant());

        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM care_history
                WHERE done_at >= ? AND done_at < ?
                """, Long.class, from, to);
        return count != null ? count : 0L;
    }

    @Transactional(readOnly = true)
    public long countDistinctActiveUsersLast24h() {
        Timestamp threshold = Timestamp.from(Instant.now().minus(Duration.ofHours(24)));
        Long count = jdbc.queryForObject("""
                SELECT count(DISTINCT p.user_id) FROM care_history ch
                JOIN plants p ON p.id = ch.plant_id
                WHERE ch.done_at >= ?
                """, Long.class, threshold);
        return count != null ? count : 0L;
    }

    @Transactional(readOnly = true)
    public List<StuckUserDto> findTopStuckUsers(int limit) {
        Timestamp threshold = Timestamp.from(Instant.now().minus(Duration.ofHours(24)));
        return jdbc.query("""
                SELECT id, telegram_chat_id, username, conversation_state, updated_at
                FROM users
                WHERE conversation_state != 'IDLE'
                  AND updated_at < ?
                ORDER BY updated_at ASC
                LIMIT ?
                """, (rs, n) -> {
            Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
            long hours = Duration.between(updatedAt, Instant.now()).toHours();
            return new StuckUserDto(
                    rs.getLong("id"),
                    rs.getLong("telegram_chat_id"),
                    rs.getString("username"),
                    rs.getString("conversation_state"),
                    updatedAt,
                    hours
            );
        }, threshold, limit);
    }

    @Transactional(readOnly = true)
    public List<RecentCareActionDto> findRecentCareActions(int limit) {
        return jdbc.query("""
                SELECT ch.id AS history_id,
                       ch.done_at,
                       ch.task_type,
                       ch.was_on_time,
                       p.id AS plant_id,
                       p.name AS plant_name,
                       u.id AS user_id,
                       u.username
                FROM care_history ch
                JOIN plants p ON p.id = ch.plant_id
                JOIN users u ON u.id = p.user_id
                ORDER BY ch.done_at DESC
                LIMIT ?
                """, (rs, n) -> new RecentCareActionDto(
                        rs.getLong("history_id"),
                        rs.getTimestamp("done_at").toInstant(),
                        rs.getString("task_type"),
                        rs.getBoolean("was_on_time"),
                        rs.getLong("user_id"),
                        rs.getString("username"),
                        rs.getLong("plant_id"),
                        rs.getString("plant_name")),
                limit);
    }
}

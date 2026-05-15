package com.plantcare.bot.admin.users.repository;

import com.plantcare.bot.admin.users.dto.CareHistoryItemDto;
import com.plantcare.bot.admin.users.dto.NotificationLogItemDto;
import com.plantcare.bot.admin.users.dto.UserDetailDto;
import com.plantcare.bot.admin.users.dto.UserPlantDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AdminUserDetailRepository {

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public Optional<UserDetailDto> findProfile(long id) {
        // Используем .query вместо .queryForObject, чтобы избежать исключения, если записи нет
        return jdbc.query("""
                SELECT id, telegram_chat_id, username, timezone,
                       quiet_hours_start, quiet_hours_end, paused_until,
                       conversation_state, is_blocked, created_at
                FROM users WHERE id = ?
                """, this::mapProfile, id).stream().findFirst();
    }

    private UserDetailDto mapProfile(ResultSet rs, int n) throws SQLException {
        Time qhs = rs.getTime("quiet_hours_start");
        Time qhe = rs.getTime("quiet_hours_end");
        return new UserDetailDto(
                rs.getLong("id"),
                rs.getLong("telegram_chat_id"),
                rs.getString("username"),
                rs.getString("timezone"),
                qhs != null ? qhs.toLocalTime() : null,
                qhe != null ? qhe.toLocalTime() : null,
                ts(rs.getTimestamp("paused_until")),
                rs.getString("conversation_state"),
                rs.getBoolean("is_blocked"),
                ts(rs.getTimestamp("created_at")),
                List.of(), List.of(), List.of(), // Списки инициализируем пустыми
                java.util.Set.of()                // feature flags подставит Service
        );
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    @Transactional(readOnly = true)
    public List<UserPlantDto> findPlants(long userId) {
        return jdbc.query("""
                SELECT p.id, p.name,
                       sp.name AS species_name,
                       r.name AS room_name,
                       cs.next_due_at AS watering_next_due,
                       p.archived_at
                FROM plants p
                LEFT JOIN species sp ON sp.id = p.species_id
                LEFT JOIN rooms r ON r.id = p.room_id
                LEFT JOIN care_schedules cs
                       ON cs.plant_id = p.id
                      AND cs.task_type = 'WATERING'
                      AND cs.is_active = true
                WHERE p.user_id = ?
                ORDER BY (p.archived_at IS NULL) DESC, p.created_at DESC
                """, (rs, n) -> new UserPlantDto(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("species_name"),
                        rs.getString("room_name"),
                        ts(rs.getTimestamp("watering_next_due")),
                        ts(rs.getTimestamp("archived_at"))),
                userId);
    }

    @Transactional(readOnly = true)
    public List<CareHistoryItemDto> findCareHistory(long userId, int limit) {
        return jdbc.query("""
                SELECT ch.id, ch.done_at, ch.task_type, ch.was_on_time,
                       p.id AS plant_id,
                       p.name AS plant_name -- ДОБАВИЛ АЛИАС ТУТ
                FROM care_history ch
                JOIN plants p ON p.id = ch.plant_id
                WHERE p.user_id = ?
                ORDER BY ch.done_at DESC
                LIMIT ?
                """, (rs, n) -> new CareHistoryItemDto(
                        rs.getLong("id"),
                        ts(rs.getTimestamp("done_at")),
                        rs.getString("task_type"),
                        rs.getBoolean("was_on_time"),
                        rs.getLong("plant_id"),
                        rs.getString("plant_name")), // Теперь совпадает с SQL
                userId, limit);
    }

    @Transactional(readOnly = true)
    public List<NotificationLogItemDto> findNotifications(long userId, int limit) {
        return jdbc.query("""
                SELECT nl.id, nl.sent_at, nl.task_type, nl.notification_type,
                       p.id AS plant_id, p.name AS plant_name
                FROM notifications_log nl
                JOIN plants p ON p.id = nl.plant_id
                WHERE p.user_id = ?
                ORDER BY nl.sent_at DESC
                LIMIT ?
                """, (rs, n) -> new NotificationLogItemDto(
                        rs.getLong("id"),
                        ts(rs.getTimestamp("sent_at")),
                        rs.getString("task_type"),
                        rs.getString("notification_type"),
                        rs.getLong("plant_id"),
                        rs.getString("plant_name")),
                userId, limit);
    }
}
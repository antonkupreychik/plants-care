package com.plantcare.bot.admin.users;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AdminUserActionRepository {

    private final JdbcTemplate jdbc;

    public int resetState(long userId) {
        return jdbc.update("""
                UPDATE users
                SET conversation_state = 'IDLE', state_data = NULL, updated_at = now()
                WHERE id = ?
                """, userId);
    }

    public int setBlocked(long userId, boolean blocked) {
        return jdbc.update(
                "UPDATE users SET is_blocked = ?, updated_at = now() WHERE id = ?",
                blocked, userId);
    }

    public int setPausedUntil(long userId, Instant until) {
        return jdbc.update(
                "UPDATE users SET paused_until = ?, updated_at = now() WHERE id = ?",
                until == null ? null : Timestamp.from(until), userId);
    }

    public Optional<UserBasicInfo> findBasic(long userId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, telegram_chat_id, username, is_blocked, paused_until
                    FROM users WHERE id = ?
                    """, (rs, n) -> new UserBasicInfo(
                            rs.getLong("id"),
                            rs.getLong("telegram_chat_id"),
                            rs.getString("username"),
                            rs.getBoolean("is_blocked"),
                            rs.getTimestamp("paused_until") != null
                                    ? rs.getTimestamp("paused_until").toInstant() : null),
                    userId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public record UserBasicInfo(
            long id,
            long telegramChatId,
            String username,
            boolean blocked,
            Instant pausedUntil
    ) {}
}

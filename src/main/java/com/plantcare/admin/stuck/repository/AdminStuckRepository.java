package com.plantcare.admin.stuck.repository;

import com.plantcare.admin.stuck.dto.StuckUserItemDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Запросы для /admin/stuck (issue #59). Узкий репо — только агрегаты для
 * админ-страницы, чтобы не мешать пользовательскому {@code UserRepository}.
 */
@Repository
@RequiredArgsConstructor
public class AdminStuckRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Юзеры, застрявшие в не-IDLE состоянии дольше {@code cutoff}.
     * Сортировка: самые старые сверху (наибольший {@code stuckHours} вверху).
     *
     * <p>SQL-нативно, чтобы:
     *   - стать-data джоинов не было (просто плоская таблица)
     *   - не вытягивать связи Plant/CareSchedule по lazy-fetch.
     */
    public List<StuckUserItemDto> findStuck(LocalDateTime cutoff, LocalDateTime now) {
        return jdbcTemplate.query("""
                SELECT id, telegram_chat_id, username, conversation_state,
                       state_data, updated_at
                FROM users
                WHERE conversation_state <> 'IDLE'
                  AND updated_at < ?
                  AND is_blocked = false
                ORDER BY updated_at ASC
                """,
                (rs, rowNum) -> {
                    long userId = rs.getLong("id");
                    long chatId = rs.getLong("telegram_chat_id");
                    String username = rs.getString("username");
                    String state = rs.getString("conversation_state");
                    String stateData = rs.getString("state_data");
                    Timestamp ts = rs.getTimestamp("updated_at");
                    LocalDateTime updatedAt = ts == null ? null : ts.toLocalDateTime();

                    long hours = updatedAt == null
                            ? 0
                            : Math.max(0, Duration.between(updatedAt, now).toHours());

                    return new StuckUserItemDto(
                            userId, chatId, username, state, stateData, updatedAt, hours
                    );
                },
                cutoff);
    }
}

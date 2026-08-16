package com.plantcare.admin.notifications.repository;

import com.plantcare.admin.notifications.dto.ChannelHealthDto;
import com.plantcare.admin.notifications.dto.ErrorCodeCountDto;
import com.plantcare.admin.notifications.dto.HourlyPointDto;
import com.plantcare.admin.notifications.dto.ProblemUserDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Агрегаты журнала доставок для /admin/notifications/health (issue #95).
 *
 * <p>Узкий admin-репозиторий на {@code JdbcTemplate} — как {@code AdminStuckRepository}:
 * дашборду нужны GROUP BY / FILTER / оконные вещи, которые в JPA выражаются
 * плохо, а сущности целиком тут не нужны вовсе.
 *
 * <p>Фильтр по каналу везде опционален: {@code null} = все каналы. Он вшит в SQL
 * как {@code (CAST(? AS VARCHAR) IS NULL OR channel = CAST(? AS VARCHAR))} — один
 * план запроса вместо конкатенации строк. CAST обязателен: без него Postgres не
 * может вывести тип параметра в {@code ? IS NULL} и валит запрос ещё на парсинге.
 */
@Repository
@RequiredArgsConstructor
public class AdminNotificationHealthRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Сводка по каналам за окно. Каналы, по которым за окно не было ни одной
     * попытки, в результате отсутствуют — дозаполняет их сервис.
     */
    public List<ChannelHealthDto> summarizeByChannel(Instant since) {
        return jdbcTemplate.query("""
                SELECT channel,
                       count(*)                                          AS total,
                       count(*) FILTER (WHERE status = 'SENT')           AS sent,
                       count(*) FILTER (WHERE status = 'FAILED')         AS failed,
                       count(*) FILTER (WHERE status = 'RATE_LIMITED')   AS rate_limited
                FROM notification_delivery_events
                WHERE created_at >= ?
                GROUP BY channel
                ORDER BY channel
                """,
                (rs, rowNum) -> new ChannelHealthDto(
                        rs.getString("channel"),
                        rs.getLong("total"),
                        rs.getLong("sent"),
                        rs.getLong("failed"),
                        rs.getLong("rate_limited")),
                Timestamp.from(since));
    }

    /** Почасовые бакеты за окно: одна строка на пару (час, канал). */
    public List<HourlyPointDto> hourlyBuckets(Instant since) {
        return jdbcTemplate.query("""
                SELECT date_trunc('hour', created_at) AS bucket,
                       channel,
                       count(*)                                  AS total,
                       count(*) FILTER (WHERE status <> 'SENT')  AS failed
                FROM notification_delivery_events
                WHERE created_at >= ?
                GROUP BY 1, 2
                ORDER BY 1, 2
                """,
                (rs, rowNum) -> new HourlyPointDto(
                        rs.getTimestamp("bucket").toInstant(),
                        rs.getString("channel"),
                        rs.getLong("total"),
                        rs.getLong("failed")),
                Timestamp.from(since));
    }

    /**
     * Топ кодов ошибок за окно.
     *
     * @param channel фильтр по каналу; {@code null} — все каналы
     */
    public List<ErrorCodeCountDto> topErrorCodes(Instant since, String channel, int limit) {
        return jdbcTemplate.query("""
                SELECT channel, error_code, count(*) AS cnt
                FROM notification_delivery_events
                WHERE created_at >= ?
                  AND error_code IS NOT NULL
                  AND (CAST(? AS VARCHAR) IS NULL OR channel = CAST(? AS VARCHAR))
                GROUP BY channel, error_code
                ORDER BY cnt DESC, channel, error_code
                LIMIT ?
                """,
                (rs, rowNum) -> new ErrorCodeCountDto(
                        rs.getString("channel"),
                        rs.getString("error_code"),
                        rs.getLong("cnt")),
                Timestamp.from(since), channel, channel, limit);
    }

    /**
     * Юзеры, у которых после последней успешной доставки в канал накопилось
     * больше {@code minFailures} неудач подряд.
     *
     * <p>Серия ищется в пределах окна, а «последний успех» — по всей истории:
     * успех вне окна всё равно обрывает серию, иначе давно почившая проблема
     * воскресала бы при каждом расширении окна.
     *
     * @param channel фильтр по каналу; {@code null} — все каналы
     */
    public List<ProblemUserDto> findProblemUsers(Instant since, String channel, int minFailures, int limit) {
        return jdbcTemplate.query("""
                WITH last_success AS (
                    SELECT user_id, channel, max(created_at) AS success_at
                    FROM notification_delivery_events
                    WHERE status = 'SENT' AND user_id IS NOT NULL
                    GROUP BY user_id, channel
                ),
                streak AS (
                    SELECT e.user_id,
                           e.channel,
                           count(*)                                                     AS failures,
                           max(e.created_at)                                            AS last_failure_at,
                           (array_agg(e.error_code ORDER BY e.created_at DESC))[1]      AS last_error_code
                    FROM notification_delivery_events e
                    LEFT JOIN last_success s
                           ON s.user_id = e.user_id AND s.channel = e.channel
                    WHERE e.user_id IS NOT NULL
                      AND e.status <> 'SENT'
                      AND e.created_at >= ?
                      AND (s.success_at IS NULL OR e.created_at > s.success_at)
                      AND (CAST(? AS VARCHAR) IS NULL OR e.channel = CAST(? AS VARCHAR))
                    GROUP BY e.user_id, e.channel
                )
                SELECT st.user_id,
                       st.channel,
                       st.failures,
                       st.last_failure_at,
                       st.last_error_code,
                       u.username,
                       u.telegram_chat_id,
                       (SELECT count(*) FROM user_devices d WHERE d.user_id = st.user_id) AS device_count
                FROM streak st
                JOIN users u ON u.id = st.user_id
                WHERE st.failures > ?
                ORDER BY st.failures DESC, st.last_failure_at DESC
                LIMIT ?
                """,
                (rs, rowNum) -> {
                    Timestamp lastFailure = rs.getTimestamp("last_failure_at");
                    long chatId = rs.getLong("telegram_chat_id");
                    // wasNull() относится к ПОСЛЕДНЕМУ чтению — снимаем флаг сразу,
                    // до любого другого rs.getXxx, иначе он расскажет про чужую колонку.
                    Long telegramChatId = rs.wasNull() ? null : chatId;
                    return new ProblemUserDto(
                            rs.getLong("user_id"),
                            rs.getString("username"),
                            telegramChatId,
                            rs.getString("channel"),
                            rs.getLong("failures"),
                            rs.getString("last_error_code"),
                            lastFailure == null ? null : lastFailure.toInstant(),
                            rs.getLong("device_count"));
                },
                Timestamp.from(since), channel, channel, minFailures, limit);
    }

    /** Push-токены всех устройств юзера — для «отправить тестовый push». */
    public List<String> findPushTokens(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT push_token FROM user_devices WHERE user_id = ?", String.class, userId);
    }

    /** Отцепить все устройства юзера («отписать токен»). Возвращает число удалённых записей. */
    public int deleteDevices(long userId) {
        return jdbcTemplate.update("DELETE FROM user_devices WHERE user_id = ?", userId);
    }
}

package com.plantcare.core.errorlog;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

/**
 * Запись журнала ошибок в {@code error_logs} (issue #97).
 *
 * <p>Простой JDBC, не JPA — как и у {@code RateLimitEventRepository} (issue #92), и по
 * тем же причинам, только острее:
 * <ul>
 *   <li>инсерт идёт из фонового потока флаша, где нет ни request-scope, ни открытой
 *       Hibernate-сессии — тащить туда EntityManager не за чем;</li>
 *   <li>журнал ошибок обязан быть невидим для бизнес-транзакций: {@code REQUIRES_NEW}
 *       гарантирует, что откат бизнес-логики не унесёт с собой запись об ошибке, и
 *       наоборот;</li>
 *   <li>меньше слоёв между аппендером и БД — меньше шансов, что сам journal-путь
 *       залогирует WARN и устроит петлю обратной связи.</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class ErrorLogRepository {

    private static final String INSERT_SQL = """
            INSERT INTO error_logs (created_at, level, logger_name, message, exception_class,
                                    fingerprint, stack_trace, user_id, request_path,
                                    correlation_id, thread_name)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    /**
     * Батч-инсерт пачки событий. Вызывается только из фонового потока
     * {@link ErrorLogRecorder}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertBatch(List<ErrorLogEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ErrorLogEntry e = entries.get(i);
                ps.setTimestamp(1, Timestamp.from(e.occurredAt()));
                ps.setString(2, e.level());
                ps.setString(3, e.loggerName());
                ps.setString(4, e.message());
                ps.setString(5, e.exceptionClass());
                ps.setString(6, e.fingerprint());
                ps.setString(7, e.stackTrace());
                if (e.userId() == null) {
                    ps.setNull(8, Types.BIGINT);
                } else {
                    ps.setLong(8, e.userId());
                }
                ps.setString(9, e.requestPath());
                ps.setString(10, e.correlationId());
                ps.setString(11, e.threadName());
            }

            @Override
            public int getBatchSize() {
                return entries.size();
            }
        });
    }

    /**
     * Удаляет всё старше {@code threshold}. Возвращает число удалённых строк.
     */
    @Transactional
    public int deleteOlderThan(Instant threshold) {
        return jdbc.update("DELETE FROM error_logs WHERE created_at < ?", Timestamp.from(threshold));
    }
}

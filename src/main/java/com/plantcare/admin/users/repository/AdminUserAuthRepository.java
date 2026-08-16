package com.plantcare.admin.users.repository;

import com.plantcare.admin.users.dto.AuthProviderKind;
import com.plantcare.admin.users.dto.UserIdentitiesDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Чтение и разрыв привязок провайдеров аутентификации (issue #93).
 *
 * <p>JdbcTemplate — как и в остальном admin-слое ({@link AdminUserDetailRepository},
 * {@link AdminUserActionRepository}): админка читает плоские проекции, JPA-графы
 * ей не нужны.
 */
@Repository
@RequiredArgsConstructor
public class AdminUserAuthRepository {

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public Optional<UserIdentitiesDto> findIdentities(long userId) {
        return jdbc.query("""
                SELECT id, telegram_chat_id, email, email_verified,
                       apple_subject, google_subject, device_id
                FROM users WHERE id = ?
                """, this::mapIdentities, userId).stream().findFirst();
    }

    private UserIdentitiesDto mapIdentities(ResultSet rs, int rowNum) throws SQLException {
        // wasNull() относится к последней прочитанной колонке — снимаем флаг
        // сразу, до чтения остальных полей.
        long chatId = rs.getLong("telegram_chat_id");
        boolean chatIdIsNull = rs.wasNull();
        return new UserIdentitiesDto(
                rs.getLong("id"),
                chatIdIsNull ? null : chatId,
                rs.getString("email"),
                rs.getBoolean("email_verified"),
                rs.getString("apple_subject"),
                rs.getString("google_subject"),
                rs.getString("device_id"));
    }

    /**
     * Обнуляет идентификатор провайдера и одновременно поднимает эпоху
     * refresh-токенов ({@code tokens_valid_from}, issue #178): разрыв привязки
     * должен выкидывать уже выданные сессии, иначе «отвязали угнанный Google»
     * не отбирает доступ у угонщика до истечения refresh-токена.
     *
     * <p>SQL по каждому провайдеру — отдельная константа, имя колонки в запрос
     * не подставляется.
     *
     * @return число обновлённых строк (0 — юзера нет)
     */
    @Transactional
    public int unlink(long userId, AuthProviderKind provider, Instant tokenEpoch) {
        String sql = switch (provider) {
            case TELEGRAM -> """
                    UPDATE users SET telegram_chat_id = NULL, tokens_valid_from = ?
                    WHERE id = ?
                    """;
            case EMAIL -> """
                    UPDATE users SET email = NULL, email_verified = false, tokens_valid_from = ?
                    WHERE id = ?
                    """;
            case APPLE -> """
                    UPDATE users SET apple_subject = NULL, tokens_valid_from = ?
                    WHERE id = ?
                    """;
            case GOOGLE -> """
                    UPDATE users SET google_subject = NULL, tokens_valid_from = ?
                    WHERE id = ?
                    """;
        };
        return jdbc.update(sql, Timestamp.from(tokenEpoch), userId);
    }

    /** Момент последнего успешного входа по magic link (для колонки «последнее использование»). */
    @Transactional(readOnly = true)
    public Optional<Instant> findLastMagicLinkUse(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        // max() над пустой выборкой отдаёт одну строку с NULL, поэтому список
        // всегда непустой, а сам элемент может быть null — Stream.findFirst()
        // на нём упал бы с NPE.
        List<Instant> rows = jdbc.query("""
                SELECT max(consumed_at) AS last_used
                FROM magic_link_tokens
                WHERE email = ? AND consumed_at IS NOT NULL
                """, (rs, n) -> ts(rs.getTimestamp("last_used")), email);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /**
     * Строки истории привязок для email юзера, новые сверху.
     *
     * <p>Читаем {@code limit + 1} строку — лишняя нужна только чтобы понять, есть
     * ли следующая страница, и в результат не попадает (её отбрасывает сервис).
     */
    @Transactional(readOnly = true)
    public List<MagicLinkRow> findLinkHistory(String email, Instant from, Instant to,
                                              int offset, int limit) {
        var sql = new StringBuilder("""
                SELECT created_at, expires_at, consumed_at
                FROM magic_link_tokens
                WHERE email = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(email);
        if (from != null) {
            sql.append(" AND created_at >= ?\n");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND created_at < ?\n");
            args.add(Timestamp.from(to));
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);

        return jdbc.query(sql.toString(), (rs, n) -> new MagicLinkRow(
                ts(rs.getTimestamp("created_at")),
                ts(rs.getTimestamp("expires_at")),
                ts(rs.getTimestamp("consumed_at"))), args.toArray());
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    /** Сырая строка {@code magic_link_tokens}; в DTO её превращает сервис. */
    public record MagicLinkRow(Instant createdAt, Instant expiresAt, Instant consumedAt) {
    }
}

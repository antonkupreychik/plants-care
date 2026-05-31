package com.plantcare.core.repository;

import com.plantcare.core.domain.RevokedRefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface RevokedRefreshTokenRepository extends JpaRepository<RevokedRefreshToken, UUID> {

    /**
     * Атомарно регистрирует jti как отозванный. Возвращает число вставленных
     * строк: {@code 1} — мы первыми отозвали этот токен, {@code 0} — он уже был
     * отозван (повторное использование / гонка ротации). На вызывающей стороне
     * {@code 0} → {@code TOKEN_REVOKED}.
     *
     * <p>{@code ON CONFLICT DO NOTHING} делает операцию идемпотентной без явной
     * блокировки.
     */
    @Modifying
    @Query(value = """
        INSERT INTO revoked_refresh_tokens (jti, expires_at)
        VALUES (:jti, :expiresAt)
        ON CONFLICT (jti) DO NOTHING
        """, nativeQuery = true)
    int revokeIfAbsent(@Param("jti") UUID jti, @Param("expiresAt") Instant expiresAt);

    boolean existsByJti(UUID jti);

    /** Чистка протухших записей (@Scheduled). Возвращает число удалённых строк. */
    @Modifying
    @Query("DELETE FROM RevokedRefreshToken r WHERE r.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}

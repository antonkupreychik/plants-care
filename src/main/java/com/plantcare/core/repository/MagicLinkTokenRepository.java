package com.plantcare.core.repository;

import com.plantcare.core.domain.MagicLinkToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface MagicLinkTokenRepository extends JpaRepository<MagicLinkToken, Long> {

    Optional<MagicLinkToken> findByTokenHash(String tokenHash);

    /**
     * Атомарно «гасит» токен по хешу: проставляет {@code consumed_at}, только
     * если токен ещё не использован и не истёк. Возвращает число обновлённых
     * строк: {@code 1} — токен валиден и теперь погашен (one-time), {@code 0} —
     * не найден / уже использован / истёк (replay-защита).
     */
    @Modifying
    @Query("""
        UPDATE MagicLinkToken t
           SET t.consumedAt = :now
         WHERE t.tokenHash = :tokenHash
           AND t.consumedAt IS NULL
           AND t.expiresAt > :now
        """)
    int consumeIfValid(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    /** Чистка протухших токенов (@Scheduled). Возвращает число удалённых строк. */
    @Modifying
    @Query("DELETE FROM MagicLinkToken t WHERE t.expiresAt < :threshold")
    int deleteExpiredBefore(@Param("threshold") Instant threshold);
}

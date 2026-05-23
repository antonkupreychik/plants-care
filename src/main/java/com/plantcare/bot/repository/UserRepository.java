package com.plantcare.bot.repository;

import com.plantcare.bot.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByTelegramChatId(Long telegramChatId);

    Optional<User> findByTelegramChatId(Long telegramChatId);

    /** Issue #79: lookup for public {@code GET /calendar/{token}.ics}. */
    Optional<User> findByCalendarToken(String calendarToken);

    /**
     * Issue #79: pessimistic SELECT FOR UPDATE used during lazy calendar-token
     * generation, чтобы два параллельных нажатия на кнопку «Экспорт» не
     * сгенерили два разных токена для одного юзера.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    @Modifying
    @Query(value = """
        INSERT INTO users (telegram_chat_id, username, timezone, conversation_state, is_blocked)
        VALUES (:chatId, :username, 'Europe/Minsk', 'IDLE', false)
        ON CONFLICT (telegram_chat_id) DO NOTHING
        """, nativeQuery = true)
    void insertOrIgnore(@Param("chatId") Long chatId, @Param("username") String username);

    // ===== Broadcast queries (issue #60) =====
    // Один и тот же фильтр в двух вариантах: count для счётчика «получат N»
    // и list для самой отправки. Делаем парами.

    @Query("SELECT u FROM User u WHERE u.blocked = false")
    java.util.List<User> findAllNotBlocked();

    @Query("SELECT COUNT(u) FROM User u WHERE u.blocked = false")
    long countAllNotBlocked();

    @Query("""
        SELECT u FROM User u
         WHERE u.blocked = false
           AND (u.pausedUntil IS NULL OR u.pausedUntil <= :now)
        """)
    java.util.List<User> findOnline(@Param("now") java.time.LocalDateTime now);

    @Query("""
        SELECT COUNT(u) FROM User u
         WHERE u.blocked = false
           AND (u.pausedUntil IS NULL OR u.pausedUntil <= :now)
        """)
    long countOnline(@Param("now") java.time.LocalDateTime now);

    @Query("""
        SELECT u FROM User u
         WHERE u.blocked = false AND u.timezone IN :timezones
        """)
    java.util.List<User> findByTimezones(@Param("timezones") java.util.Collection<String> timezones);

    @Query("""
        SELECT COUNT(u) FROM User u
         WHERE u.blocked = false AND u.timezone IN :timezones
        """)
    long countByTimezones(@Param("timezones") java.util.Collection<String> timezones);

    /** Уникальные таймзоны для multi-select в форме рассылки. */
    @Query("SELECT DISTINCT u.timezone FROM User u WHERE u.blocked = false ORDER BY u.timezone")
    java.util.List<String> findDistinctTimezones();
}


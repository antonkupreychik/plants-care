package com.plantcare.core.repository;

import com.plantcare.core.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
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

    /**
     * Общий загрузчик юзера по chat_id для бот-путей (/menu, переключение/пауза
     * локаций). {@code activeLocation} — ленивый {@code @ManyToOne}, а меню
     * читается уже вне транзакции, поэтому без графа ленивый прокси даёт
     * {@code LazyInitializationException: no Session}. {@code @EntityGraph} даёт
     * дешёвый LEFT JOIN по nullable-FK и инициализирует связь в транзакции
     * загрузки. Срабатывало только при заполненном active_location_id (для NULL
     * ленивый ManyToOne и так отдаёт null без обращения к БД).
     */
    @EntityGraph(attributePaths = "activeLocation")
    Optional<User> findByTelegramChatId(Long telegramChatId);

    /** Issue #79: lookup for public {@code GET /calendar/{token}.ics}. */
    Optional<User> findByCalendarToken(String calendarToken);

    /**
     * Issue #95: только id по chat_id — для журнала доставок, который пишется на
     * воркер-потоке очереди после КАЖДОЙ отправки. Полную сущность (да ещё с
     * {@code @EntityGraph} на activeLocation) тянуть ради одного числа незачем.
     */
    @Query("SELECT u.id FROM User u WHERE u.telegramChatId = :telegramChatId")
    Optional<Long> findIdByTelegramChatId(@Param("telegramChatId") Long telegramChatId);

    // ===== Mobile auth (issue #88) =====

    Optional<User> findByEmail(String email);

    Optional<User> findByAppleSubject(String appleSubject);

    Optional<User> findByGoogleSubject(String googleSubject);

    // ===== Guest auth (issue #227) =====

    /** Поиск гостевого пользователя по deviceId устройства. */
    Optional<User> findByDeviceId(String deviceId);

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

    // ===== Vacation mode (issue #53) =====

    /**
     * Юзеры, чей отпуск заканчивается в окне {@code [from; to)}. Окно
     * полуоткрытое — чтобы при hourly-тике каждый юзер попал ровно один раз
     * (см. VacationSchedulerService).
     */
    @Query("""
        SELECT u FROM User u
         WHERE u.blocked = false
           AND u.pausedUntil IS NOT NULL
           AND u.pausedUntil >= :from
           AND u.pausedUntil < :to
        """)
    java.util.List<User> findVacationEndingBetween(
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to
    );

    /**
     * Юзеры, у которых отпуск уже завершился (или вот-вот завершится) и кому
     * нужно отправить «welcome back» + сбросить paused_until.
     */
    @Query("""
        SELECT u FROM User u
         WHERE u.blocked = false
           AND u.pausedUntil IS NOT NULL
           AND u.pausedUntil <= :now
        """)
    java.util.List<User> findVacationEnded(@Param("now") java.time.LocalDateTime now);

    /**
     * Issue #53: атомарно сбрасывает paused_until только если он ещё не null.
     * Возвращает число обновлённых строк — 0 значит юзер уже вернулся сам
     * («Вернуться сейчас») между findVacationEnded и обработкой, и
     * welcome-back посылать НЕ надо (иначе будет дубль сообщения).
     */
    @Modifying
    @Query("UPDATE User u SET u.pausedUntil = NULL WHERE u.id = :id AND u.pausedUntil IS NOT NULL")
    int clearPausedUntilIfActive(@Param("id") Long id);

    // ===== Logout-all / token epoch (issue #178) =====

    /**
     * Атомарно выставляет эпоху валидности refresh-токенов (logout-all):
     * все refresh с {@code iat < now} перестают проходить ротацию. Делаем
     * через UPDATE, чтобы не читать-модифицировать-писать с гонкой.
     */
    @Modifying
    @Query("UPDATE User u SET u.tokensValidFrom = :now WHERE u.id = :id")
    int updateTokensValidFrom(@Param("id") Long id, @Param("now") java.time.Instant now);
}


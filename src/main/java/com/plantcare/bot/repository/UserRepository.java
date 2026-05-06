package com.plantcare.bot.repository;

import com.plantcare.bot.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByTelegramChatId(Long telegramChatId);

    Optional<User> findByTelegramChatId(Long telegramChatId);

    @Modifying
    @Query(value = """
        INSERT INTO users (telegram_chat_id, username, timezone, conversation_state, is_blocked)
        VALUES (:chatId, :username, 'UTC', 'IDLE', false)
        ON CONFLICT (telegram_chat_id) DO NOTHING
        """, nativeQuery = true)
    void insertOrIgnore(@Param("chatId") Long chatId, @Param("username") String username);
}

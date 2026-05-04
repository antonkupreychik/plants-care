package com.plantcare.bot.repository;

import com.plantcare.bot.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByTelegramChatId(Long telegramChatId);

    boolean existsByTelegramChatId(Long telegramChatId);
}

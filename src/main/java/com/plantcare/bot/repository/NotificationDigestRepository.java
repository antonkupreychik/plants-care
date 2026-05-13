package com.plantcare.bot.repository;

import com.plantcare.bot.domain.NotificationDigest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationDigestRepository extends JpaRepository<NotificationDigest, Long> {
}
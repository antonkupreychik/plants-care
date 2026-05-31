package com.plantcare.core.domain;

import com.plantcare.core.domain.enums.AudienceFilter;
import com.plantcare.core.domain.enums.BroadcastStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Лог одной попытки админ-рассылки (issue #60). Состояние обновляется
 * инкрементально из @Async-таски в {@code AdminBroadcastService}; страница
 * /admin/broadcast читает то же значение для polling-прогресса.
 */
@Entity
@Table(name = "admin_broadcasts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminBroadcast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_text", nullable = false, columnDefinition = "TEXT")
    private String messageText;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience_filter", nullable = false, length = 64)
    private AudienceFilter audienceFilter;

    @Column(name = "total_count", nullable = false)
    private int totalCount;

    @Column(name = "sent_count", nullable = false)
    private int sentCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "blocked_count", nullable = false)
    private int blockedCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BroadcastStatus status;

    @Column(name = "sent_by", nullable = false, length = 64)
    private String sentBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** {@code true} если рассылка завершена (DONE/STOPPED/FAILED). */
    public boolean isTerminal() {
        return status != BroadcastStatus.RUNNING;
    }

    /** Сколько ещё юзеров осталось обработать. */
    public int remaining() {
        return Math.max(0, totalCount - sentCount - failedCount - blockedCount);
    }
}

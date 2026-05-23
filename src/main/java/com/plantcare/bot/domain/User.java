package com.plantcare.bot.domain;

import com.plantcare.bot.domain.base.BaseEntity;
import com.plantcare.bot.domain.enums.ConversationState;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseEntity {

    @Column(name = "telegram_chat_id", nullable = false, unique = true)
    private Long telegramChatId;

    @Column(length = 255)
    private String username;

    @Column(nullable = false, length = 64)
    @Builder.Default
    private String timezone = "Europe/Minsk";

    @Column(name = "quiet_hours_start", nullable = false)
    @Builder.Default
    private LocalTime quietHoursStart = LocalTime.of(22, 0);

    @Column(name = "quiet_hours_end", nullable = false)
    @Builder.Default
    private LocalTime quietHoursEnd = LocalTime.of(9, 0);

    @Column(name = "paused_until")
    private LocalDateTime pausedUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "conversation_state", nullable = false, length = 64)
    @Builder.Default
    private ConversationState conversationState = ConversationState.IDLE;

    @Type(JsonType.class)
    @Column(name = "state_data", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> stateData = new HashMap<>();

    @Column(name = "is_blocked", nullable = false)
    @Builder.Default
    private boolean blocked = false;

    /**
     * Issue #79: opaque token for public .ics calendar URL.
     * NULL until first calendar export request — generated lazily by {@link com.plantcare.bot.service.CalendarService}.
     * Uniqueness enforced by partial unique index {@code idx_users_calendar_token} (see V14 migration).
     */
    @Column(name = "calendar_token", length = 64, unique = true)
    private String calendarToken;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void resetConversation() {
        this.conversationState = ConversationState.IDLE;
        this.stateData = new HashMap<>();
    }

    public boolean isPaused() {
        return pausedUntil != null && pausedUntil.isAfter(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
    }
}
package com.plantcare.core.domain;

import com.plantcare.core.domain.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "locations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Location extends BaseEntity {

    public static final String DEFAULT_NAME = "Мои растения";
    public static final String DEFAULT_EMOJI = "🪴";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 30)
    private String name;

    @Column(length = 16)
    private String emoji;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private boolean defaultLocation = false;

    /**
     * Когда запись была изменена. Обновляется Hibernate при каждом UPDATE (issue #91).
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Момент физического удаления для синк-протокола (issue #91).
     * NULL = не удалено.
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * UUID от мобильного клиента для идемпотентности (issue #91).
     * NULL для записей Telegram-бота. Partial unique index на стороне БД.
     */
    @Column(name = "client_id", length = 64)
    private String clientId;

    public String getDisplayName() {
        if (emoji == null || emoji.isBlank()) {
            return name;
        }

        return emoji + " " + name;
    }
}

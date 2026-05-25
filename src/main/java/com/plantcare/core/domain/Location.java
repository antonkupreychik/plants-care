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

    public String getDisplayName() {
        if (emoji == null || emoji.isBlank()) {
            return name;
        }

        return emoji + " " + name;
    }
}
package com.plantcare.core.domain;

import com.plantcare.core.domain.base.BaseEntity;
import com.plantcare.core.domain.enums.CareDifficulty;
import com.plantcare.core.domain.enums.LightPreference;
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

@Entity
@Table(name = "species")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Species extends BaseEntity {

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "latin_name", length = 150)
    private String latinName;

    @Column(name = "watering_days")
    private Integer wateringDays;

    @Column(name = "misting_days")
    private Integer mistingDays;

    @Column(name = "fertilizing_days")
    private Integer fertilizingDays;

    @Column(name = "soil_check_days")
    private Integer soilCheckDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "light_preference", length = 16)
    private LightPreference lightPreference;

    @Enumerated(EnumType.STRING)
    @Column(name = "care_difficulty", length = 16)
    private CareDifficulty careDifficulty;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "search_tags", columnDefinition = "TEXT")
    private String searchTags;

    @Column(nullable = false)
    @Builder.Default
    private Integer popularity = 0;

    /**
     * Токсичность вида для кошек. Тройное состояние значимо:
     * {@code true} — токсично, {@code false} — безопасно, {@code null} — нет данных.
     */
    @Column(name = "toxic_to_cats")
    private Boolean toxicToCats;

    /**
     * Токсичность вида для собак. {@code null} — нет данных (см. {@link #toxicToCats}).
     */
    @Column(name = "toxic_to_dogs")
    private Boolean toxicToDogs;

    /**
     * Токсичность вида для людей/детей. {@code null} — нет данных (см. {@link #toxicToCats}).
     */
    @Column(name = "toxic_to_humans")
    private Boolean toxicToHumans;
}

package com.plantcare.bot.domain;

import com.plantcare.bot.domain.base.BaseEntity;
import com.plantcare.bot.domain.enums.CareDifficulty;
import com.plantcare.bot.domain.enums.LightPreference;
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
}

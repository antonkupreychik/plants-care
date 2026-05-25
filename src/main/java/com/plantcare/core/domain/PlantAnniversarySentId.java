package com.plantcare.core.domain;

import java.io.Serializable;
import java.util.Objects;

/**
 * Композитный PK для {@link PlantAnniversarySent} (issue #117).
 * Имена и типы полей должны точно соответствовать @Id-полям сущности.
 */
public class PlantAnniversarySentId implements Serializable {

    private Long plantId;
    private Integer anniversaryYear;

    public PlantAnniversarySentId() {
    }

    public PlantAnniversarySentId(Long plantId, Integer anniversaryYear) {
        this.plantId = plantId;
        this.anniversaryYear = anniversaryYear;
    }

    public Long getPlantId() {
        return plantId;
    }

    public Integer getAnniversaryYear() {
        return anniversaryYear;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PlantAnniversarySentId that)) return false;
        return Objects.equals(plantId, that.plantId)
                && Objects.equals(anniversaryYear, that.anniversaryYear);
    }

    @Override
    public int hashCode() {
        return Objects.hash(plantId, anniversaryYear);
    }
}

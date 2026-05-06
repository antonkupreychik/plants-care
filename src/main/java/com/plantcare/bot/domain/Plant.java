package com.plantcare.bot.domain;

import com.plantcare.bot.domain.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "plants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Plant extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Старое поле. Пока можно оставить, потому что в БД ещё есть room_id.
     * Позже его можно будет удалить отдельной миграцией.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "species_id")
    private Species species;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false)
    private Location location;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "photo_file_id")
    private String photoFileId;

    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    @OneToMany(mappedBy = "plant")
    @Builder.Default
    private List<CareSchedule> schedules = new ArrayList<>();

    public boolean isArchived() {
        return archivedAt != null;
    }

    public void archive() {
        this.archivedAt = LocalDateTime.now();
    }

    public void addSchedule(CareSchedule schedule) {
        if (schedules == null) {
            schedules = new ArrayList<>();
        }

        schedules.add(schedule);
        schedule.setPlant(this);
    }
}
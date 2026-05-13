package com.plantcare.bot.domain;

import com.plantcare.bot.domain.base.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "notification_digests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationDigest extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Type(JsonType.class)
    @Column(name = "plant_task_ids", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<DigestTaskItem> plantTaskIds = new ArrayList<>();
}
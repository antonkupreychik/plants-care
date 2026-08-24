package com.plantcare.core.domain;

import com.plantcare.core.domain.enums.TaskType;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Элемент дайджеста уведомлений — хранится внутри JSONB-колонки
 * {@code notification_digests.plant_task_ids}.
 *
 * <p>{@link Serializable} здесь обязателен: hypersistence-utils при deep-copy
 * значения JSONB-атрибута ({@code ObjectMapperJsonSerializer#clone}) гоняет
 * коллекцию через Java-сериализацию, и на несериализуемом элементе падает
 * {@code NonSerializableObjectException} прямо в {@code save()} — шедулер
 * дайджестов ронял из-за этого весь тик.
 */
public record DigestTaskItem(
        Long scheduleId,
        Long plantId,
        String plantName,
        TaskType taskType,
        LocalDateTime scheduledAt
) implements Serializable {
}

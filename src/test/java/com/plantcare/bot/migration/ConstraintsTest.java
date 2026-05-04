package com.plantcare.bot.migration;

import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Проверяет CHECK и UNIQUE констрейнты.
 *
 * Тесты создают живые записи, поэтому после каждого делаем cleanup, чтобы не отравлять
 * состояние следующим тестам в этой же сессии (Postgres переиспользуется).
 */
class ConstraintsTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        // Чистим только тестовые данные, сидинг видов не трогаем.
        jdbc.execute("DELETE FROM care_schedules");
        jdbc.execute("DELETE FROM plants");
        jdbc.execute("DELETE FROM rooms");
        jdbc.execute("DELETE FROM users");
    }

    @Test
    void duplicateTelegramChatIdRejected() {
        jdbc.update("INSERT INTO users (telegram_chat_id) VALUES (?)", 12345L);

        assertThatThrownBy(() ->
                jdbc.update("INSERT INTO users (telegram_chat_id) VALUES (?)", 12345L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateRoomNameForSameUserRejected() {
        Long userId = insertUser(11111L);

        jdbc.update("INSERT INTO rooms (user_id, name) VALUES (?, ?)", userId, "Кухня");

        assertThatThrownBy(() ->
                jdbc.update("INSERT INTO rooms (user_id, name) VALUES (?, ?)", userId, "Кухня"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameRoomNameForDifferentUsersAllowed() {
        Long user1 = insertUser(22222L);
        Long user2 = insertUser(33333L);

        jdbc.update("INSERT INTO rooms (user_id, name) VALUES (?, ?)", user1, "Гостиная");
        jdbc.update("INSERT INTO rooms (user_id, name) VALUES (?, ?)", user2, "Гостиная");

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rooms WHERE name = 'Гостиная'", Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void invalidTaskTypeRejected() {
        Long userId = insertUser(44444L);
        Long plantId = insertPlant(userId, "Test plant");

        assertThatThrownBy(() ->
                jdbc.update(
                        "INSERT INTO care_schedules (plant_id, task_type, interval_days, next_due_at) " +
                                "VALUES (?, 'WALKING_THE_DOG', 7, CURRENT_TIMESTAMP)",
                        plantId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void zeroIntervalRejected() {
        Long userId = insertUser(55555L);
        Long plantId = insertPlant(userId, "Test plant");

        assertThatThrownBy(() ->
                jdbc.update(
                        "INSERT INTO care_schedules (plant_id, task_type, interval_days, next_due_at) " +
                                "VALUES (?, 'WATERING', 0, CURRENT_TIMESTAMP)",
                        plantId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateScheduleTypeForPlantRejected() {
        Long userId = insertUser(66666L);
        Long plantId = insertPlant(userId, "Test plant");

        jdbc.update(
                "INSERT INTO care_schedules (plant_id, task_type, interval_days, next_due_at) " +
                        "VALUES (?, 'WATERING', 7, CURRENT_TIMESTAMP)",
                plantId);

        // Второй WATERING на то же растение — нельзя
        assertThatThrownBy(() ->
                jdbc.update(
                        "INSERT INTO care_schedules (plant_id, task_type, interval_days, next_due_at) " +
                                "VALUES (?, 'WATERING', 5, CURRENT_TIMESTAMP)",
                        plantId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // А вот MISTING для того же растения — можно
        jdbc.update(
                "INSERT INTO care_schedules (plant_id, task_type, interval_days, next_due_at) " +
                        "VALUES (?, 'MISTING', 3, CURRENT_TIMESTAMP)",
                plantId);

        Integer schedules = jdbc.queryForObject(
                "SELECT COUNT(*) FROM care_schedules WHERE plant_id = ?", Integer.class, plantId);
        assertThat(schedules).isEqualTo(2);
    }

    @Test
    void deletingRoomKeepsPlantsAndNullsRoomId() {
        Long userId = insertUser(77777L);
        Long roomId = jdbc.queryForObject(
                "INSERT INTO rooms (user_id, name) VALUES (?, ?) RETURNING id",
                Long.class, userId, "Кухня");
        Long plantId = jdbc.queryForObject(
                "INSERT INTO plants (user_id, room_id, name) VALUES (?, ?, ?) RETURNING id",
                Long.class, userId, roomId, "Базилик");

        jdbc.update("DELETE FROM rooms WHERE id = ?", roomId);

        Long roomAfter = jdbc.queryForObject(
                "SELECT room_id FROM plants WHERE id = ?", Long.class, plantId);
        assertThat(roomAfter).isNull();

        Integer plantStillExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plants WHERE id = ?", Integer.class, plantId);
        assertThat(plantStillExists).isEqualTo(1);
    }

    // --- Helpers ---

    private Long insertUser(long chatId) {
        return jdbc.queryForObject(
                "INSERT INTO users (telegram_chat_id) VALUES (?) RETURNING id",
                Long.class, chatId);
    }

    private Long insertPlant(Long userId, String name) {
        return jdbc.queryForObject(
                "INSERT INTO plants (user_id, name) VALUES (?, ?) RETURNING id",
                Long.class, userId, name);
    }
}

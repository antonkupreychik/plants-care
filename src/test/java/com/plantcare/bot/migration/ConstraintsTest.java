package com.plantcare.bot.migration;

import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConstraintsTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM care_history");
        jdbcTemplate.update("DELETE FROM care_schedules");
        jdbcTemplate.update("DELETE FROM plants");
        jdbcTemplate.update("DELETE FROM locations");
        jdbcTemplate.update("DELETE FROM rooms");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void invalidTaskTypeRejected() {
        Long userId = insertUser(76L);
        Long locationId = insertDefaultLocation(userId);
        Long plantId = insertPlant(userId, locationId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO care_schedules
                            (plant_id, task_type, interval_days, next_due_at)
                        VALUES (?, ?, ?, ?)
                        """,
                plantId,
                "INVALID_TASK",
                7,
                Timestamp.valueOf(LocalDateTime.now())
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void zeroIntervalRejected() {
        Long userId = insertUser(78L);
        Long locationId = insertDefaultLocation(userId);
        Long plantId = insertPlant(userId, locationId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO care_schedules
                            (plant_id, task_type, interval_days, next_due_at)
                        VALUES (?, ?, ?, ?)
                        """,
                plantId,
                "WATERING",
                0,
                Timestamp.valueOf(LocalDateTime.now())
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateScheduleTypeForPlantRejected() {
        Long userId = insertUser(77L);
        Long locationId = insertDefaultLocation(userId);
        Long plantId = insertPlant(userId, locationId);

        jdbcTemplate.update("""
                        INSERT INTO care_schedules
                            (plant_id, task_type, interval_days, next_due_at)
                        VALUES (?, ?, ?, ?)
                        """,
                plantId,
                "WATERING",
                7,
                Timestamp.valueOf(LocalDateTime.now())
        );

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO care_schedules
                            (plant_id, task_type, interval_days, next_due_at)
                        VALUES (?, ?, ?, ?)
                        """,
                plantId,
                "WATERING",
                10,
                Timestamp.valueOf(LocalDateTime.now().plusDays(1))
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingRoomKeepsPlantsAndNullsRoomId() {
        Long userId = insertUser(75L);
        Long locationId = insertDefaultLocation(userId);

        Long roomId = jdbcTemplate.queryForObject("""
                        INSERT INTO rooms (user_id, name, display_order)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                userId,
                "Старая комната",
                1
        );

        Long plantId = jdbcTemplate.queryForObject("""
                        INSERT INTO plants (user_id, room_id, location_id, name)
                        VALUES (?, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                userId,
                roomId,
                locationId,
                "Базилик"
        );

        jdbcTemplate.update("DELETE FROM rooms WHERE id = ?", roomId);

        Long plantsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM plants WHERE id = ?",
                Long.class,
                plantId
        );

        Long nullRoomCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM plants WHERE id = ? AND room_id IS NULL",
                Long.class,
                plantId
        );

        Long locationStillExistsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM plants WHERE id = ? AND location_id = ?",
                Long.class,
                plantId,
                locationId
        );

        assertThat(plantsCount).isEqualTo(1L);
        assertThat(nullRoomCount).isEqualTo(1L);
        assertThat(locationStillExistsCount).isEqualTo(1L);
    }

    @Test
    void duplicateLocationNameForSameUserRejectedIgnoringCase() {
        Long userId = insertUser(79L);

        insertDefaultLocation(userId);

        jdbcTemplate.update("""
                        INSERT INTO locations (user_id, name, emoji, is_default)
                        VALUES (?, ?, ?, ?)
                        """,
                userId,
                "Кухня",
                "🍳",
                false
        );

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO locations (user_id, name, emoji, is_default)
                        VALUES (?, ?, ?, ?)
                        """,
                userId,
                "кухня",
                "🌿",
                false
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyOneDefaultLocationPerUserAllowed() {
        Long userId = insertUser(80L);

        insertDefaultLocation(userId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO locations (user_id, name, emoji, is_default)
                        VALUES (?, ?, ?, ?)
                        """,
                userId,
                "Вторая дефолтная",
                "🏠",
                true
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Long insertUser(Long chatId) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO users (telegram_chat_id, timezone, conversation_state)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                chatId,
                "UTC",
                "IDLE"
        );
    }

    private Long insertDefaultLocation(Long userId) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO locations (user_id, name, emoji, is_default)
                        VALUES (?, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                userId,
                "Мои растения",
                "🪴",
                true
        );
    }

    private Long insertPlant(Long userId, Long locationId) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO plants (user_id, location_id, name)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                userId,
                locationId,
                "Test plant"
        );
    }
}
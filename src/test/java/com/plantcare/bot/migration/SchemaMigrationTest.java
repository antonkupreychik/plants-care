package com.plantcare.bot.migration;

import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет, что миграции V1 и V2 применились корректно.
 *
 * Эти тесты НЕ дублируют ddl-auto: validate (Hibernate валидирует схему под JPA-сущности
 * при старте контекста — но JPA-сущности будут только в задаче #3). Здесь же мы проверяем
 * структуру БД на уровне SQL: что таблицы созданы, индексы на месте, сидинг прошёл.
 */
class SchemaMigrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {
            "users",
            "species",
            "rooms",
            "plants",
            "care_schedules",
            "care_history",
            "notifications_log"
    })
    void allExpectedTablesExist(String tableName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_schema = 'public' AND table_name = ?",
                Integer.class,
                tableName);

        assertThat(count)
                .as("Таблица %s должна существовать", tableName)
                .isEqualTo(1);
    }

    @Test
    void flywayHistoryHasBothMigrations() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE",
                Integer.class);

        // V1 (init schema) + V2 (seed species)
        assertThat(applied).isGreaterThanOrEqualTo(2);
    }

    @Test
    void criticalIndexesExist() {
        // Проверяем самые горячие индексы — без них шедулер и поиск растений будут тормозить.
        // Список названий индексов сверяется с V1__init_schema.sql.
        String[] expectedIndexes = {
                "idx_users_telegram_chat_id",
                "idx_species_popularity",
                "idx_species_search_tags",
                "idx_rooms_user_id",
                "idx_plants_user_room",
                "idx_schedules_next_due",
                "idx_history_plant_date",
                "idx_notif_plant_task_sent"
        };

        for (String indexName : expectedIndexes) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pg_indexes " +
                            "WHERE schemaname = 'public' AND indexname = ?",
                    Integer.class,
                    indexName);

            assertThat(count)
                    .as("Индекс %s должен существовать", indexName)
                    .isEqualTo(1);
        }
    }

    @Test
    void updatedAtTriggersAreInstalled() {
        // Триггеры на updated_at — критичны для корректного отслеживания изменений.
        Integer triggerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.triggers " +
                        "WHERE event_object_schema = 'public' " +
                        "AND trigger_name IN ('trg_users_updated_at', 'trg_plants_updated_at', 'trg_schedules_updated_at')",
                Integer.class);

        assertThat(triggerCount).isEqualTo(3);
    }
}

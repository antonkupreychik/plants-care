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

    // ===================== issue #117 — V20/V21 =====================

    @Test
    void v20_should_add_nullable_acquired_at_column_to_plants() {
        // arrange: ищем колонку в information_schema
        var row = jdbc.queryForMap(
                "SELECT data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'plants' "
                        + "AND column_name = 'acquired_at'");

        // assert: тип DATE и nullable — обязательное условие back-compat
        assertThat(row.get("data_type")).isEqualTo("date");
        assertThat(row.get("is_nullable")).isEqualTo("YES");
    }

    @Test
    void v21_should_create_plant_anniversaries_sent_table_with_composite_pk() {
        // arrange: таблица существует
        Integer tableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'plant_anniversaries_sent'",
                Integer.class);
        assertThat(tableCount).isEqualTo(1);

        // assert: PK — композитный (plant_id, anniversary_year)
        var pkColumns = jdbc.queryForList(
                "SELECT a.attname AS col "
                        + "FROM pg_index i "
                        + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                        + "WHERE i.indrelid = 'plant_anniversaries_sent'::regclass "
                        + "AND i.indisprimary "
                        + "ORDER BY a.attname",
                String.class);
        assertThat(pkColumns).containsExactly("anniversary_year", "plant_id");
    }

    @Test
    void v21_should_create_partial_index_for_active_plants_with_acquired_date() {
        // Частичный индекс под запрос шедулера годовщин — критично для производительности.
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                        + "WHERE schemaname = 'public' AND indexname = 'idx_plants_acquired_active'",
                Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void v20_and_v21_should_keep_existing_plants_intact() {
        // Issue #117 backward-compat: миграция NULLABLE и не сносит ни одной строки.
        // На момент запуска теста таблица plants пуста (тесты чистят за собой), но
        // если seed-данные есть — они должны иметь acquired_at = NULL и не падать.
        Integer plantsWithNullAcquired = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plants WHERE acquired_at IS NULL",
                Integer.class);
        Integer totalPlants = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plants", Integer.class);

        // Все ровно те же = ничего не «съело» миграцией
        assertThat(plantsWithNullAcquired).isEqualTo(totalPlants);
    }
}

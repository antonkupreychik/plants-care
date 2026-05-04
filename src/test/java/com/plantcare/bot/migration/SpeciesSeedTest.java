package com.plantcare.bot.migration;

import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет, что сидинг видов из V2 применился корректно.
 * Если позже добавим V3+ с новыми видами — счётчик нужно обновить.
 */
class SpeciesSeedTest extends IntegrationTestBase {

    private static final int EXPECTED_SEEDED_SPECIES = 30;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void allSpeciesSeeded() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM species", Integer.class);
        assertThat(count).isEqualTo(EXPECTED_SEEDED_SPECIES);
    }

    @Test
    void monsteraHasExpectedDefaults() {
        // Spot-check на самом популярном виде — если что-то сломается в порядке колонок
        // или энкодинге, это тут отловится.
        Map<String, Object> monstera = jdbc.queryForMap(
                "SELECT name, watering_days, misting_days, fertilizing_days, " +
                        "light_preference, care_difficulty, popularity " +
                        "FROM species WHERE name = 'Монстера'");

        assertThat(monstera).containsEntry("watering_days", 7);
        assertThat(monstera).containsEntry("misting_days", 3);
        assertThat(monstera).containsEntry("fertilizing_days", 30);
        assertThat(monstera).containsEntry("light_preference", "PARTIAL");
        assertThat(monstera).containsEntry("care_difficulty", "EASY");
        assertThat(monstera).containsEntry("popularity", 100);
    }

    @Test
    void cactusAndSucculentsHaveNoMisting() {
        // У кактусов и суккулентов misting_days должен быть NULL —
        // иначе бот будет предлагать им опрыскивание, что бессмысленно.
        List<String> noMistingSpecies = jdbc.queryForList(
                "SELECT name FROM species WHERE misting_days IS NULL ORDER BY name",
                String.class);

        assertThat(noMistingSpecies).contains("Кактус", "Суккулент", "Алоэ", "Толстянка");
    }

    @Test
    void allSeededSpeciesHaveValidEnumValues() {
        // CHECK-констрейнты должны были это поймать на этапе миграции,
        // но дополнительная проверка не повредит и зафиксирует контракт.
        Integer invalidLight = jdbc.queryForObject(
                "SELECT COUNT(*) FROM species WHERE light_preference IS NOT NULL " +
                        "AND light_preference NOT IN ('SHADE', 'PARTIAL', 'BRIGHT', 'DIRECT')",
                Integer.class);
        assertThat(invalidLight).isZero();

        Integer invalidDifficulty = jdbc.queryForObject(
                "SELECT COUNT(*) FROM species WHERE care_difficulty IS NOT NULL " +
                        "AND care_difficulty NOT IN ('EASY', 'MEDIUM', 'HARD')",
                Integer.class);
        assertThat(invalidDifficulty).isZero();
    }

    @Test
    void searchTagsAreSearchableViaTsvector() {
        // Проверяем, что GIN-индекс по tsvector работает для поиска, который
        // понадобится в задаче #8.
        Integer matches = jdbc.queryForObject(
                "SELECT COUNT(*) FROM species " +
                        "WHERE to_tsvector('simple', coalesce(search_tags, '')) " +
                        "      @@ to_tsquery('simple', ?)",
                Integer.class,
                "monstera");
        // Должна найтись Монстера (search_tags содержит 'monstera')
        assertThat(matches).isGreaterThanOrEqualTo(1);
    }
}

package com.plantcare.core.repository;

import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Limit;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @DataJpaTest на реальном Postgres (Testcontainers, НЕ H2) для запросов месячного
 * отчёта (issue #137). Цель — прогнать на Postgres-диалекте именно те конструкции,
 * где H2 врёт: {@code GROUP BY}-проекции и особенно
 * {@code HAVING MIN(CASE WHEN was_on_time ...)} в {@code findFlawlessPlantsByUserInRange}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Testcontainers
@DisplayName("Запросы месячного отчёта на Postgres (issue #137)")
class MonthlyReportQueriesRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withReuse(true);

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private UserRepository userRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private PlantRepository plantRepository;
    @Autowired private CareHistoryRepository careHistoryRepository;
    @Autowired private EntityManager entityManager;

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 4, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 5, 1, 0, 0);

    @Test
    @DisplayName("should_count_only_active_actions_in_range")
    void should_count_only_active_actions_in_range() {
        // arrange
        User user = newUser(9001L);
        Plant plant = newPlant(user, "Фикус", LocalDate.of(2024, 1, 1));
        // 2 в апреле (in range), 1 в марте (out), 1 в мае (out)
        care(plant, TaskType.WATERING, LocalDateTime.of(2026, 4, 10, 12, 0), true);
        care(plant, TaskType.WATERING, LocalDateTime.of(2026, 4, 20, 12, 0), true);
        care(plant, TaskType.WATERING, LocalDateTime.of(2026, 3, 31, 23, 0), true);
        care(plant, TaskType.WATERING, LocalDateTime.of(2026, 5, 1, 0, 0), true);
        // одна отменённая в апреле — не должна считаться
        CareHistory cancelled = care(plant, TaskType.WATERING, LocalDateTime.of(2026, 4, 15, 12, 0), true);
        CareHistory comp = care(plant, TaskType.WATERING, LocalDateTime.of(2026, 4, 16, 12, 0), true);
        cancelled.setCancelledBy(comp);
        careHistoryRepository.save(cancelled);
        entityManager.flush();

        // act: активных в [апрель] = 10-е, 20-е, 16-е(comp) = 3 (15-е отменено)
        long count = careHistoryRepository.countActiveByUserIdInRange(user.getId(), FROM, TO);

        // assert
        assertThat(count).isEqualTo(3L);
    }

    @Test
    @DisplayName("should_group_active_actions_by_task_type")
    void should_group_active_actions_by_task_type() {
        // arrange
        User user = newUser(9002L);
        Plant plant = newPlant(user, "Монстера", LocalDate.of(2024, 1, 1));
        care(plant, TaskType.WATERING, LocalDateTime.of(2026, 4, 5, 12, 0), true);
        care(plant, TaskType.WATERING, LocalDateTime.of(2026, 4, 12, 12, 0), true);
        care(plant, TaskType.MISTING, LocalDateTime.of(2026, 4, 14, 12, 0), false);
        care(plant, TaskType.FERTILIZING, LocalDateTime.of(2026, 4, 18, 12, 0), true);
        entityManager.flush();

        // act
        Map<TaskType, Long> breakdown = careHistoryRepository
                .countActiveByUserIdGroupedByTaskType(user.getId(), FROM, TO)
                .stream()
                .collect(Collectors.toMap(
                        CareHistoryRepository.TaskTypeCount::getTaskType,
                        CareHistoryRepository.TaskTypeCount::getCount));

        // assert
        assertThat(breakdown)
                .containsEntry(TaskType.WATERING, 2L)
                .containsEntry(TaskType.MISTING, 1L)
                .containsEntry(TaskType.FERTILIZING, 1L)
                .doesNotContainKey(TaskType.SOIL_CHECK);
    }

    @Test
    @DisplayName("should_return_flawless_plant_when_all_actions_on_time")
    void should_return_flawless_plant_when_all_actions_on_time() {
        // arrange: два растения. Идеальное — все on-time; прогульщик — есть один false.
        User user = newUser(9003L);
        Plant flawless = newPlant(user, "Идеальное", LocalDate.of(2024, 1, 1));
        Plant missed = newPlant(user, "Прогульщик", LocalDate.of(2024, 1, 1));

        care(flawless, TaskType.WATERING, LocalDateTime.of(2026, 4, 5, 12, 0), true);
        care(flawless, TaskType.WATERING, LocalDateTime.of(2026, 4, 15, 12, 0), true);

        care(missed, TaskType.WATERING, LocalDateTime.of(2026, 4, 6, 12, 0), true);
        care(missed, TaskType.WATERING, LocalDateTime.of(2026, 4, 16, 12, 0), false);
        care(missed, TaskType.WATERING, LocalDateTime.of(2026, 4, 26, 12, 0), true);
        entityManager.flush();

        // act
        List<CareHistoryRepository.PlantActionCount> result = careHistoryRepository
                .findFlawlessPlantsByUserInRange(user.getId(), FROM, TO, Limit.of(5));

        // assert: только идеальное растение (HAVING MIN(CASE...) отсёк прогульщика)
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPlantId()).isEqualTo(flawless.getId());
        assertThat(result.get(0).getPlantName()).isEqualTo("Идеальное");
        assertThat(result.get(0).getCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("should_order_flawless_by_action_count_desc")
    void should_order_flawless_by_action_count_desc() {
        // arrange: два идеальных растения, у одного больше действий → оно первым.
        User user = newUser(9004L);
        Plant few = newPlant(user, "Мало", LocalDate.of(2024, 1, 1));
        Plant many = newPlant(user, "Много", LocalDate.of(2024, 1, 1));

        care(few, TaskType.WATERING, LocalDateTime.of(2026, 4, 5, 12, 0), true);

        care(many, TaskType.WATERING, LocalDateTime.of(2026, 4, 5, 12, 0), true);
        care(many, TaskType.WATERING, LocalDateTime.of(2026, 4, 12, 12, 0), true);
        care(many, TaskType.MISTING, LocalDateTime.of(2026, 4, 19, 12, 0), true);
        entityManager.flush();

        // act
        List<CareHistoryRepository.PlantActionCount> result = careHistoryRepository
                .findFlawlessPlantsByUserInRange(user.getId(), FROM, TO, Limit.of(5));

        // assert
        assertThat(result).extracting(CareHistoryRepository.PlantActionCount::getPlantName)
                .containsExactly("Много", "Мало");
    }

    @Test
    @DisplayName("should_exclude_archived_plant_from_flawless")
    void should_exclude_archived_plant_from_flawless() {
        // arrange: идеальное, но архивное растение
        User user = newUser(9005L);
        Plant archived = newPlant(user, "Архивное идеальное", LocalDate.of(2024, 1, 1));
        archived.setArchivedAt(LocalDateTime.of(2026, 4, 30, 12, 0));
        plantRepository.save(archived);
        care(archived, TaskType.WATERING, LocalDateTime.of(2026, 4, 5, 12, 0), true);
        care(archived, TaskType.WATERING, LocalDateTime.of(2026, 4, 15, 12, 0), true);
        entityManager.flush();

        // act
        List<CareHistoryRepository.PlantActionCount> result = careHistoryRepository
                .findFlawlessPlantsByUserInRange(user.getId(), FROM, TO, Limit.of(5));

        // assert
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should_find_oldest_living_plant_by_acquired_date")
    void should_find_oldest_living_plant_by_acquired_date() {
        // arrange
        User user = newUser(9006L);
        newPlant(user, "Молодое", LocalDate.of(2025, 6, 1));
        Plant oldest = newPlant(user, "Самое старое", LocalDate.of(2020, 1, 1));
        newPlant(user, "Среднее", LocalDate.of(2023, 1, 1));
        Plant archivedOlder = newPlant(user, "Архивный древний", LocalDate.of(2010, 1, 1));
        archivedOlder.setArchivedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        plantRepository.save(archivedOlder);
        entityManager.flush();

        // act
        List<Plant> result = plantRepository.findOldestLivingByUser(user.getId(), Limit.of(1));

        // assert: архивный 2010 исключён, самое старое живое — 2020.
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(oldest.getId());
        assertThat(result.get(0).getName()).isEqualTo("Самое старое");
    }

    // ===================== helpers =====================

    private User newUser(Long chatId) {
        return userRepository.save(User.builder()
                .telegramChatId(chatId)
                .timezone("Europe/Moscow")
                .blocked(false)
                .build());
    }

    private int locationSeq = 0;

    private Plant newPlant(User user, String name, LocalDate acquiredAt) {
        Location location = locationRepository.save(Location.builder()
                .user(user)
                .name("Loc " + (++locationSeq))
                .emoji(Location.DEFAULT_EMOJI)
                .defaultLocation(false)
                .build());
        return plantRepository.save(Plant.builder()
                .user(user)
                .location(location)
                .name(name)
                .acquiredAt(acquiredAt)
                .build());
    }

    private CareHistory care(Plant plant, TaskType type, LocalDateTime doneAt, boolean onTime) {
        return careHistoryRepository.save(CareHistory.builder()
                .plant(plant)
                .taskType(type)
                .doneAt(doneAt)
                .onTime(onTime)
                .build());
    }
}

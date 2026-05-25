package com.plantcare.core.repository;

import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты {@link CareHistoryRepository#existsActiveByPlantIdAndTaskTypeInRange}
 * (issue #118): метод используется для идемпотентности ретро-отметки, поэтому
 * критично проверить настоящее поведение границ окна и cancelled-фильтр на боевом Postgres.
 */
@DisplayName("CareHistoryRepository#existsActiveByPlantIdAndTaskTypeInRange (#118)")
class CareHistoryRepositoryTest extends IntegrationTestBase {

    @Autowired private UserRepository userRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private PlantRepository plantRepository;
    @Autowired private CareHistoryRepository careHistoryRepository;

    // Окно произвольной точкой отсчёта: [2026-05-24 00:00; 2026-05-25 00:00).
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 5, 24, 0, 0);
    private static final LocalDateTime TO_EXCLUSIVE = LocalDateTime.of(2026, 5, 25, 0, 0);

    @AfterEach
    void cleanup() {
        careHistoryRepository.deleteAll();
        plantRepository.deleteAll();
        locationRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Активная запись внутри окна → true")
    void should_return_true_when_active_entry_inside_window() {
        User user = saveUser(2000L);
        Plant plant = savePlant(user, "Монстера");
        saveHistory(plant, TaskType.WATERING,
                LocalDateTime.of(2026, 5, 24, 12, 0), null);

        boolean exists = careHistoryRepository.existsActiveByPlantIdAndTaskTypeInRange(
                plant.getId(), TaskType.WATERING, FROM, TO_EXCLUSIVE);

        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("Запись точно на нижней границе 'from' → true (inclusive)")
    void should_treat_from_boundary_as_inclusive() {
        User user = saveUser(2001L);
        Plant plant = savePlant(user, "Монстера");
        saveHistory(plant, TaskType.WATERING, FROM, null);

        boolean exists = careHistoryRepository.existsActiveByPlantIdAndTaskTypeInRange(
                plant.getId(), TaskType.WATERING, FROM, TO_EXCLUSIVE);

        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("Запись точно на верхней границе 'toExclusive' → false (exclusive)")
    void should_treat_to_boundary_as_exclusive() {
        User user = saveUser(2002L);
        Plant plant = savePlant(user, "Монстера");
        saveHistory(plant, TaskType.WATERING, TO_EXCLUSIVE, null);

        boolean exists = careHistoryRepository.existsActiveByPlantIdAndTaskTypeInRange(
                plant.getId(), TaskType.WATERING, FROM, TO_EXCLUSIVE);

        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("Запись внутри окна, но другого taskType → false")
    void should_ignore_other_task_types() {
        User user = saveUser(2003L);
        Plant plant = savePlant(user, "Монстера");
        saveHistory(plant, TaskType.MISTING,
                LocalDateTime.of(2026, 5, 24, 12, 0), null);

        boolean exists = careHistoryRepository.existsActiveByPlantIdAndTaskTypeInRange(
                plant.getId(), TaskType.WATERING, FROM, TO_EXCLUSIVE);

        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("Запись для другого растения внутри окна → false")
    void should_ignore_other_plants() {
        User user = saveUser(2004L);
        Plant target = savePlant(user, "Монстера");
        Plant other = savePlant(user, "Фикус");

        saveHistory(other, TaskType.WATERING,
                LocalDateTime.of(2026, 5, 24, 12, 0), null);

        boolean exists = careHistoryRepository.existsActiveByPlantIdAndTaskTypeInRange(
                target.getId(), TaskType.WATERING, FROM, TO_EXCLUSIVE);

        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("Cancelled-запись (cancelledBy != null) игнорируется → false")
    void should_ignore_cancelled_entries() {
        User user = saveUser(2005L);
        Plant plant = savePlant(user, "Монстера");

        // Компенсирующая запись (она «активна»)
        CareHistory compensation = saveHistory(plant, TaskType.WATERING,
                LocalDateTime.of(2026, 5, 26, 9, 0), null);

        // Оригинальная запись внутри окна, перекрытая компенсацией → не должна считаться.
        saveHistory(plant, TaskType.WATERING,
                LocalDateTime.of(2026, 5, 24, 12, 0), compensation);

        boolean exists = careHistoryRepository.existsActiveByPlantIdAndTaskTypeInRange(
                plant.getId(), TaskType.WATERING, FROM, TO_EXCLUSIVE);

        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("Запись ДО окна → false")
    void should_return_false_when_entry_before_window() {
        User user = saveUser(2006L);
        Plant plant = savePlant(user, "Монстера");
        saveHistory(plant, TaskType.WATERING,
                LocalDateTime.of(2026, 5, 23, 23, 59, 59), null);

        boolean exists = careHistoryRepository.existsActiveByPlantIdAndTaskTypeInRange(
                plant.getId(), TaskType.WATERING, FROM, TO_EXCLUSIVE);

        assertThat(exists).isFalse();
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private User saveUser(Long chatId) {
        return userRepository.save(User.builder()
                .telegramChatId(chatId)
                .timezone("UTC")
                .blocked(false)
                .build());
    }

    private Location saveDefaultLocation(User user) {
        return locationRepository.findByUserIdAndDefaultLocationTrue(user.getId())
                .orElseGet(() -> locationRepository.save(Location.builder()
                        .user(user)
                        .name(Location.DEFAULT_NAME)
                        .emoji(Location.DEFAULT_EMOJI)
                        .defaultLocation(true)
                        .build()));
    }

    private Plant savePlant(User user, String name) {
        Location location = saveDefaultLocation(user);
        return plantRepository.save(Plant.builder()
                .user(user)
                .location(location)
                .name(name)
                .build());
    }

    private CareHistory saveHistory(Plant plant, TaskType type, LocalDateTime doneAt,
                                    CareHistory cancelledBy) {
        return careHistoryRepository.save(CareHistory.builder()
                .plant(plant)
                .taskType(type)
                .doneAt(doneAt.truncatedTo(ChronoUnit.MICROS))
                .onTime(true)
                .cancelledBy(cancelledBy)
                .build());
    }
}

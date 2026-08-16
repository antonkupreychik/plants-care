package com.plantcare.core.service;

import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.LocationRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест {@link AccountDeletionService} (issue #181, GDPR / App Store).
 *
 * <p>Проверяет, что hard-delete строки {@code users} каскадно удаляет связанные
 * данные через FK {@code ON DELETE CASCADE} (растения, расписания, локации и т.д.)
 * и что повторный вызов идемпотентен (no-op, без исключений).
 *
 * <p>Контейнер Postgres переиспользуется, как в остальных IT. Каждый тест
 * работает на уникальных telegram chatId (диапазон 9000–9009) и удаляет
 * только свои строки в {@code @AfterEach}.
 */
@DisplayName("AccountDeletionService — hard-delete аккаунта и идемпотентность (#181)")
class AccountDeletionServiceIT extends IntegrationTestBase {

    @Autowired
    private AccountDeletionService accountDeletionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private PlantRepository plantRepository;

    @Autowired
    private CareScheduleRepository careScheduleRepository;

    // Сущности, вставленные текущим тестом (для очистки если тест не завершил удаление).
    private final List<CareSchedule> createdSchedules = new ArrayList<>();
    private final List<Plant> createdPlants = new ArrayList<>();
    private final List<Location> createdLocations = new ArrayList<>();
    private final List<User> createdUsers = new ArrayList<>();

    @AfterEach
    void cleanupOwnRowsOnly() {
        // Каскадное удаление через FK делает deleteAll по расписаниям/растениям/локациям
        // нет-опом, если юзер уже удалён тестом. deleteAll по несуществующим ID — safe.
        createdSchedules.removeIf(s -> !careScheduleRepository.existsById(s.getId()));
        careScheduleRepository.deleteAll(createdSchedules);

        createdPlants.removeIf(p -> !plantRepository.existsById(p.getId()));
        plantRepository.deleteAll(createdPlants);

        createdLocations.removeIf(l -> !locationRepository.existsById(l.getId()));
        locationRepository.deleteAll(createdLocations);

        createdUsers.removeIf(u -> !userRepository.existsById(u.getId()));
        userRepository.deleteAll(createdUsers);

        createdSchedules.clear();
        createdPlants.clear();
        createdLocations.clear();
        createdUsers.clear();
    }

    @Test
    @DisplayName("Удаление юзера каскадно уничтожает растения и расписания через FK ON DELETE CASCADE")
    void should_cascade_delete_plants_and_schedules_when_user_is_deleted() {
        // arrange
        User user = saveUser(9000L, "Europe/Moscow");
        Plant plant = savePlant(user, "Монстера");
        CareSchedule schedule = saveSchedule(plant, TaskType.WATERING);

        // act
        accountDeletionService.deleteAccount(user.getId());

        // assert — пользователь, растение и расписание удалены каскадом
        assertThat(userRepository.existsById(user.getId())).isFalse();
        assertThat(plantRepository.existsById(plant.getId())).isFalse();
        assertThat(careScheduleRepository.existsById(schedule.getId())).isFalse();
    }

    @Test
    @DisplayName("Удаление юзера каскадно уничтожает локации через FK ON DELETE CASCADE")
    void should_cascade_delete_locations_when_user_is_deleted() {
        // arrange
        User user = saveUser(9001L, "Asia/Almaty");
        // saveDefaultLocation создаёт локацию в БД
        Location location = saveDefaultLocation(user);

        // act
        accountDeletionService.deleteAccount(user.getId());

        // assert
        assertThat(userRepository.existsById(user.getId())).isFalse();
        assertThat(locationRepository.existsById(location.getId())).isFalse();
    }

    @Test
    @DisplayName("Повторный DELETE идемпотентен — no-op, исключения не бросаются")
    void should_be_idempotent_when_user_already_deleted() {
        // arrange
        User user = saveUser(9002L, "UTC");

        // act — первый вызов удаляет
        accountDeletionService.deleteAccount(user.getId());
        assertThat(userRepository.existsById(user.getId())).isFalse();

        // act — повторный вызов не бросает исключения (204 tolerant)
        accountDeletionService.deleteAccount(user.getId());

        // assert — по-прежнему не существует
        assertThat(userRepository.existsById(user.getId())).isFalse();
    }

    @Test
    @DisplayName("Несуществующий userId обрабатывается идемпотентно — no-op без исключений")
    void should_handle_nonexistent_user_id_gracefully() {
        // arrange — юзера с id 999_999 не существует
        long nonExistentId = 999_999L;

        // act + assert — никакого исключения
        accountDeletionService.deleteAccount(nonExistentId);
        assertThat(userRepository.existsById(nonExistentId)).isFalse();
    }

    @Test
    @DisplayName("Удаление юзера из Almaty (не UTC) — таймзона не влияет на каскад")
    void should_cascade_delete_all_data_for_user_in_non_utc_timezone() {
        // arrange — юзер в Asia/Almaty (UTC+5) с растением и расписанием
        User user = saveUser(9003L, "Asia/Almaty");
        Plant plant = savePlant(user, "Алоэ");
        CareSchedule schedule = saveSchedule(plant, TaskType.MISTING);

        // act
        accountDeletionService.deleteAccount(user.getId());

        // assert
        assertThat(userRepository.existsById(user.getId())).isFalse();
        assertThat(plantRepository.existsById(plant.getId())).isFalse();
        assertThat(careScheduleRepository.existsById(schedule.getId())).isFalse();
    }

    // ====================================================================
    // fixtures
    // ====================================================================

    private User saveUser(Long chatId, String timezone) {
        User user = userRepository.save(User.builder()
                .telegramChatId(chatId)
                .timezone(timezone)
                .blocked(false)
                .quietHoursStart(LocalTime.of(22, 0))
                .quietHoursEnd(LocalTime.of(9, 0))
                .build());
        createdUsers.add(user);
        return user;
    }

    private Location saveDefaultLocation(User user) {
        return locationRepository.findByUserIdAndDefaultLocationTrue(user.getId())
                .orElseGet(() -> {
                    Location location = locationRepository.save(Location.builder()
                            .user(user)
                            .name(Location.DEFAULT_NAME)
                            .emoji(Location.DEFAULT_EMOJI)
                            .defaultLocation(true)
                            .build());
                    createdLocations.add(location);
                    return location;
                });
    }

    private Plant savePlant(User user, String name) {
        Location location = saveDefaultLocation(user);
        Plant plant = plantRepository.save(Plant.builder()
                .user(user)
                .location(location)
                .name(name)
                .build());
        createdPlants.add(plant);
        return plant;
    }

    private CareSchedule saveSchedule(Plant plant, TaskType taskType) {
        CareSchedule schedule = careScheduleRepository.save(CareSchedule.builder()
                .plant(plant)
                .taskType(taskType)
                .intervalDays(7)
                .nextDueAt(LocalDateTime.of(2026, 6, 1, 6, 0))
                .active(true)
                .build());
        createdSchedules.add(schedule);
        return schedule;
    }
}

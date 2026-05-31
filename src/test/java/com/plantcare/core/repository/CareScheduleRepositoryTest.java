package com.plantcare.core.repository;

import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CareScheduleRepositoryTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private PlantRepository plantRepository;

    @Autowired
    private CareScheduleRepository careScheduleRepository;

    @Autowired
    private SpeciesRepository speciesRepository;

    /**
     * Виды, созданные тестом. Чистим ТОЧЕЧНО (а не {@code deleteAll}), иначе при
     * глобально включённом Testcontainers reuse удалили бы Flyway-сид видов из
     * общего контейнера и обвалили все seed-зависимые тесты (см. MEMORY build-tool).
     */
    private final List<Long> createdSpeciesIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        careScheduleRepository.deleteAll();
        plantRepository.deleteAll();
        locationRepository.deleteAll();
        userRepository.deleteAll();
        createdSpeciesIds.forEach(speciesRepository::deleteById);
        createdSpeciesIds.clear();
    }

    @Test
    void findDueSchedulesReturnsOnlyOverdueAndActive() {
        User user = saveUser(100L);
        Plant plant = savePlant(user, "Монстера");

        CareSchedule overdueActive = saveSchedule(
                plant,
                TaskType.WATERING,
                LocalDateTime.now().minusHours(1),
                true
        );

        saveSchedule(
                plant,
                TaskType.MISTING,
                LocalDateTime.now().plusDays(1),
                true
        );

        saveSchedule(
                plant,
                TaskType.FERTILIZING,
                LocalDateTime.now().minusHours(2),
                false
        );

        List<CareSchedule> result = careScheduleRepository.findDueSchedules(
                LocalDateTime.now()
        );

        assertThat(result)
                .extracting(CareSchedule::getId)
                .containsExactly(overdueActive.getId());
    }

    @Test
    void findDueSchedulesIgnoresArchivedPlants() {
        User user = saveUser(101L);
        Plant plant = savePlant(user, "Мёртвая монстера");
        plant.archive();
        plantRepository.save(plant);

        saveSchedule(
                plant,
                TaskType.WATERING,
                LocalDateTime.now().minusHours(1),
                true
        );

        List<CareSchedule> result = careScheduleRepository.findDueSchedules(
                LocalDateTime.now()
        );

        assertThat(result).isEmpty();
    }

    @Test
    void findDueSchedulesIgnoresBlockedUsers() {
        User user = saveUser(102L);
        user.setBlocked(true);
        userRepository.save(user);

        Plant plant = savePlant(user, "Монстера");

        saveSchedule(
                plant,
                TaskType.WATERING,
                LocalDateTime.now().minusHours(1),
                true
        );

        List<CareSchedule> result = careScheduleRepository.findDueSchedules(
                LocalDateTime.now()
        );

        assertThat(result).isEmpty();
    }

    @Test
    void findDueSchedulesAvoidsNPlusOne() {
        User user = saveUser(103L);

        for (int i = 0; i < 3; i++) {
            Plant plant = savePlant(user, "Plant " + i);

            saveSchedule(
                    plant,
                    TaskType.WATERING,
                    LocalDateTime.now().minusHours(i + 1),
                    true
            );
        }

        List<CareSchedule> result = careScheduleRepository.findDueSchedules(
                LocalDateTime.now()
        );

        assertThat(result).hasSize(3);

        for (CareSchedule schedule : result) {
            assertThat(schedule.getPlant()).isNotNull();
            assertThat(schedule.getPlant().getUser()).isNotNull();
            assertThat(schedule.getPlant().getLocation()).isNotNull();
        }
    }

    @Test
    void findByPlantIdAndTaskTypeReturnsCorrectSchedule() {
        User user = saveUser(104L);
        Plant plant = savePlant(user, "Монстера");

        CareSchedule watering = saveSchedule(
                plant,
                TaskType.WATERING,
                LocalDateTime.now().plusDays(1),
                true
        );

        saveSchedule(
                plant,
                TaskType.MISTING,
                LocalDateTime.now().plusDays(2),
                true
        );

        var result = careScheduleRepository.findByPlantIdAndTaskType(
                plant.getId(),
                TaskType.WATERING
        );

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(watering.getId());
    }

    @Test
    void findUserSchedulesDueBeforeIncludesOnlyUserOwn() {
        User user1 = saveUser(105L);
        User user2 = saveUser(106L);

        Plant plant1 = savePlant(user1, "User1's Plant");
        Plant plant2 = savePlant(user2, "User2's Plant");

        CareSchedule user1Schedule = saveSchedule(
                plant1,
                TaskType.WATERING,
                LocalDateTime.now().minusHours(1),
                true
        );

        saveSchedule(
                plant2,
                TaskType.WATERING,
                LocalDateTime.now().minusHours(1),
                true
        );

        List<CareSchedule> result = careScheduleRepository.findUserSchedulesDueBefore(
                user1.getId(),
                LocalDateTime.now()
        );

        assertThat(result)
                .extracting(CareSchedule::getId)
                .containsExactly(user1Schedule.getId());
    }

    @Test
    void findUserSchedulesDueBeforeFetchesSpecies() {
        // arrange — растение с видом; сессия теста закрыта на момент ассерта,
        // поэтому доступ к species доказывает, что LEFT JOIN FETCH его инициализировал (G6)
        User user = saveUser(108L);
        Species species = saveSpecies("Monstera deliciosa");
        Plant plant = savePlant(user, "Монстера", species);

        saveSchedule(plant, TaskType.WATERING, LocalDateTime.now().minusHours(1), true);

        // act
        List<CareSchedule> result = careScheduleRepository.findUserSchedulesDueBefore(
                user.getId(),
                LocalDateTime.now()
        );

        // assert — связь подтянута без LazyInitializationException
        assertThat(result).hasSize(1);
        Species fetched = result.get(0).getPlant().getSpecies();
        assertThat(fetched).isNotNull();
        assertThat(fetched.getName()).isEqualTo("Monstera deliciosa");
    }

    @Test
    void findUserSchedulesDueBeforeWorksWhenPlantHasNoSpecies() {
        // arrange — растение без вида: LEFT JOIN FETCH не должен отбрасывать строку (edge)
        User user = saveUser(109L);
        Plant plant = savePlant(user, "Безымянное");

        CareSchedule schedule = saveSchedule(
                plant, TaskType.WATERING, LocalDateTime.now().minusHours(1), true);

        // act
        List<CareSchedule> result = careScheduleRepository.findUserSchedulesDueBefore(
                user.getId(),
                LocalDateTime.now()
        );

        // assert — растение возвращается, species == null
        assertThat(result)
                .extracting(CareSchedule::getId)
                .containsExactly(schedule.getId());
        assertThat(result.get(0).getPlant().getSpecies()).isNull();
    }

    @Test
    void rescheduleFromUpdatesNextDueAt() {
        User user = saveUser(107L);
        Plant plant = savePlant(user, "Монстера");

        CareSchedule schedule = saveSchedule(
                plant,
                TaskType.WATERING,
                LocalDateTime.now().minusDays(1),
                true
        );

        LocalDateTime doneAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        schedule.rescheduleFrom(doneAt);

        CareSchedule saved = careScheduleRepository.save(schedule);

        assertThat(saved.getNextDueAt())
                .isEqualTo(doneAt.plusDays(saved.getIntervalDays()));
    }

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
        return savePlant(user, name, null);
    }

    private Plant savePlant(User user, String name, Species species) {
        Location location = saveDefaultLocation(user);

        return plantRepository.save(Plant.builder()
                .user(user)
                .location(location)
                .name(name)
                .species(species)
                .build());
    }

    private Species saveSpecies(String name) {
        Species species = speciesRepository.save(Species.builder()
                .name(name)
                .build());
        createdSpeciesIds.add(species.getId());
        return species;
    }

    private CareSchedule saveSchedule(
            Plant plant,
            TaskType taskType,
            LocalDateTime nextDueAt,
            boolean active
    ) {
        return careScheduleRepository.save(CareSchedule.builder()
                .plant(plant)
                .taskType(taskType)
                .intervalDays(7)
                .nextDueAt(nextDueAt.truncatedTo(ChronoUnit.MICROS))
                .active(active)
                .build());
    }
}
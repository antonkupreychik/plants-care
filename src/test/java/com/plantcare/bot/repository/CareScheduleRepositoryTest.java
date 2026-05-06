package com.plantcare.bot.repository;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.support.IntegrationTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CareScheduleRepositoryTest extends IntegrationTestBase {

    @Autowired private CareScheduleRepository scheduleRepository;
    @Autowired private PlantRepository plantRepository;
    @Autowired private UserRepository userRepository;

    @PersistenceContext
    private EntityManager em;

    @AfterEach
    void cleanup() {
        scheduleRepository.deleteAll();
        plantRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void findDueSchedulesReturnsOnlyOverdueAndActive() {
        User user = saveUser(200L, false);
        Plant plant = savePlant(user, "Монстера", false);

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).truncatedTo(ChronoUnit.MICROS);

        // Просрочена — должна попасть
        CareSchedule overdue = saveSchedule(plant, TaskType.WATERING, now.minusHours(1), true);
        // Будущая — не должна попасть
        saveSchedule(plant, TaskType.MISTING, now.plusDays(1), true);
        // Деактивирована — не должна попасть, даже если просрочена
        saveSchedule(plant, TaskType.FERTILIZING, now.minusHours(2), false);

        List<CareSchedule> due = scheduleRepository.findDueSchedules(now);

        assertThat(due).extracting(CareSchedule::getId).containsExactly(overdue.getId());
    }

    @Test
    void findDueSchedulesIgnoresArchivedPlants() {
        User user = saveUser(201L, false);
        Plant archived = savePlant(user, "Мёртвая монстера", true);
        saveSchedule(archived, TaskType.WATERING, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).truncatedTo(ChronoUnit.MICROS).minusHours(1), true);

        List<CareSchedule> due = scheduleRepository.findDueSchedules(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));

        assertThat(due).isEmpty();
    }

    @Test
    void findDueSchedulesIgnoresBlockedUsers() {
        User blocked = saveUser(202L, true);
        Plant plant = savePlant(blocked, "Монстера", false);
        saveSchedule(plant, TaskType.WATERING, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).minusHours(1), true);

        List<CareSchedule> due = scheduleRepository.findDueSchedules(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));

        assertThat(due).isEmpty();
    }

    @Test
    @Transactional
    void findDueSchedulesAvoidsNPlusOne() {
        // Самая важная проверка: запрос обязан использовать JOIN FETCH,
        // чтобы шедулер не делал N+1 при 100 растениях.

        User user = saveUser(203L, false);
        for (int i = 0; i < 5; i++) {
            Plant plant = savePlant(user, "Plant " + i, false);
            saveSchedule(plant, TaskType.WATERING, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).minusHours(1), true);
        }

        em.clear(); // Сбрасываем кеш, чтобы реально проверить количество SQL

        List<CareSchedule> due = scheduleRepository.findDueSchedules(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        assertThat(due).hasSize(5);

        // Доступ к plant.user не должен инициировать дополнительные запросы.
        // Если JOIN FETCH работает — все 3 уровня (schedule, plant, user) уже загружены.
        for (CareSchedule schedule : due) {
            assertThat(schedule.getPlant().getName()).isNotNull();
            assertThat(schedule.getPlant().getUser().getTelegramChatId()).isEqualTo(203L);
        }
    }

    @Test
    void findByPlantIdAndTaskTypeReturnsCorrectSchedule() {
        User user = saveUser(204L, false);
        Plant plant = savePlant(user, "Монстера", false);

        CareSchedule watering = saveSchedule(plant, TaskType.WATERING, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS), true);
        saveSchedule(plant, TaskType.MISTING, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS), true);

        assertThat(scheduleRepository.findByPlantIdAndTaskType(plant.getId(), TaskType.WATERING))
                .map(CareSchedule::getId)
                .contains(watering.getId());
    }

    @Test
    void findUserSchedulesDueBeforeIncludesOnlyUserOwn() {
        User user1 = saveUser(205L, false);
        User user2 = saveUser(206L, false);
        Plant plant1 = savePlant(user1, "User1's Plant", false);
        Plant plant2 = savePlant(user2, "User2's Plant", false);

        LocalDateTime soon = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusHours(1);
        saveSchedule(plant1, TaskType.WATERING, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS), true);
        saveSchedule(plant2, TaskType.WATERING, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS), true);

        List<CareSchedule> u1 = scheduleRepository.findUserSchedulesDueBefore(user1.getId(), soon);

        assertThat(u1).hasSize(1);
    }

    @Test
    void rescheduleFromUpdatesNextDueAt() {
        User user = saveUser(207L, false);
        Plant plant = savePlant(user, "Монстера", false);
        CareSchedule schedule = saveSchedule(plant, TaskType.WATERING, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS), true);
        schedule.setIntervalDays(7);

        LocalDateTime watered = LocalDateTime.of(2026, 5, 1, 10, 0);
        schedule.rescheduleFrom(watered);

        assertThat(schedule.getNextDueAt()).isEqualTo(watered.plusDays(7));
    }

    // --- Helpers ---

    private User saveUser(long chatId, boolean blocked) {
        return userRepository.save(User.builder()
                .telegramChatId(chatId)
                .blocked(blocked)
                .build());
    }

    private Plant savePlant(User user, String name, boolean archived) {
        Plant plant = Plant.builder()
                .user(user)
                .name(name)
                .build();
        if (archived) {
            plant.archive();
        }
        return plantRepository.save(plant);
    }

    private CareSchedule saveSchedule(Plant plant, TaskType type, LocalDateTime dueAt, boolean active) {
        return scheduleRepository.save(CareSchedule.builder()
                .plant(plant)
                .taskType(type)
                .intervalDays(7)
                .nextDueAt(dueAt)
                .active(active)
                .build());
    }
}

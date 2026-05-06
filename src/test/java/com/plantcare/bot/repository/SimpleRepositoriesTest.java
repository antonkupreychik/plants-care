package com.plantcare.bot.repository;

import com.plantcare.bot.domain.CareHistory;
import com.plantcare.bot.domain.NotificationLog;
import com.plantcare.bot.domain.enums.NotificationType;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.Room;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke-тесты на репозитории, которые в основном используют стандартные методы JPA —
 * нет смысла писать тест на каждый findBy. Здесь проверяем только то, что нетривиально:
 * корректность сортировки и работа boolean exists* / count* методов.
 */
class SimpleRepositoriesTest extends IntegrationTestBase {

    @Autowired private UserRepository userRepository;
    @Autowired private RoomRepository roomRepository;
    @Autowired private PlantRepository plantRepository;
    @Autowired private CareHistoryRepository historyRepository;
    @Autowired private NotificationLogRepository notificationRepository;

    @AfterEach
    void cleanup() {
        notificationRepository.deleteAll();
        historyRepository.deleteAll();
        plantRepository.deleteAll();
        roomRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void roomsSortedByDisplayOrderThenName() {
        User user = userRepository.save(User.builder().telegramChatId(300L).build());

        roomRepository.save(Room.builder().user(user).name("Спальня").displayOrder(2).build());
        roomRepository.save(Room.builder().user(user).name("Кухня").displayOrder(1).build());
        roomRepository.save(Room.builder().user(user).name("Гостиная").displayOrder(1).build());

        var rooms = roomRepository.findAllByUserIdOrderByDisplayOrderAscNameAsc(user.getId());

        // displayOrder=1 → Гостиная, Кухня (по алфавиту), потом displayOrder=2 → Спальня
        assertThat(rooms).extracting(Room::getName)
                .containsExactly("Гостиная", "Кухня", "Спальня");
    }

    @Test
    void plantArchiveExcludesFromActiveListings() {
        User user = userRepository.save(User.builder().telegramChatId(301L).build());
        plantRepository.save(Plant.builder().user(user).name("Активное").build());
        Plant archived = plantRepository.save(Plant.builder().user(user).name("Архивное").build());
        archived.archive();
        plantRepository.save(archived);

        assertThat(plantRepository.countByUserIdAndArchivedAtIsNull(user.getId())).isEqualTo(1);
        assertThat(plantRepository.findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(user.getId()))
                .extracting(Plant::getName)
                .containsExactly("Активное");
    }

    @Test
    void careHistoryFirstByPlantAndTaskReturnsLatest() {
        User user = userRepository.save(User.builder().telegramChatId(302L).build());
        Plant plant = plantRepository.save(Plant.builder().user(user).name("Монстера").build());

        historyRepository.save(CareHistory.builder()
                .plant(plant).taskType(TaskType.WATERING)
                .doneAt(LocalDateTime.of(2026, 5, 1, 10, 0))
                .onTime(true).build());
        CareHistory latest = historyRepository.save(CareHistory.builder()
                .plant(plant).taskType(TaskType.WATERING)
                .doneAt(LocalDateTime.of(2026, 5, 4, 10, 0))
                .onTime(true).build());

        var found = historyRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(
                plant.getId(), TaskType.WATERING);

        assertThat(found).map(CareHistory::getId).contains(latest.getId());
    }

    @Test
    void notificationDedupeCheckWorks() {
        User user = userRepository.save(User.builder().telegramChatId(303L).build());
        Plant plant = plantRepository.save(Plant.builder().user(user).name("Монстера").build());

        notificationRepository.save(NotificationLog.builder()
                .plant(plant)
                .taskType(TaskType.WATERING)
                .notificationType(NotificationType.DUE)
                .sentAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).minusHours(2))
                .build());

        // За последние 6 часов есть запись — true
        assertThat(notificationRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(
                plant.getId(), TaskType.WATERING, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).minusHours(6))).isTrue();

        // За последние 30 минут — нет
        assertThat(notificationRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(
                plant.getId(), TaskType.WATERING, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).minusMinutes(30))).isFalse();

        // Другой тип задачи — нет
        assertThat(notificationRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(
                plant.getId(), TaskType.MISTING, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).minusHours(6))).isFalse();
    }
}

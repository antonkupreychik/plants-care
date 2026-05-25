package com.plantcare.core.service;

import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.PlantAnniversarySent;
import com.plantcare.core.domain.PlantAnniversarySentId;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.LocationRepository;
import com.plantcare.core.repository.PlantAnniversarySentRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты сервиса годовщин растений (issue #117, Part C) на реальном
 * Postgres через Testcontainers.
 *
 * <p>Проверяет ключевые AC:
 *   - happy path: при наступлении календарной годовщины в TZ юзера сервис
 *     возвращает Anniversary с правильным yearsOld и текстом.
 *   - возраст < 1 год отсекается (НЕ присылаем «0 лет»).
 *   - идемпотентность через {@link PlantAnniversarySentRepository}.
 *   - не-UTC TZ: момент 23:00Z + Asia/Almaty (UTC+5) → уже 04:00 следующего дня
 *     в TZ юзера, годовщина должна сработать в этот «следующий» календарный день.
 *   - архивные растения не получают годовщин.
 */
@DisplayName("PlantAnniversaryService — годовщины растений (issue #117)")
class PlantAnniversaryServiceTest extends IntegrationTestBase {

    @Autowired private PlantAnniversaryService anniversaryService;

    @Autowired private UserRepository userRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private PlantRepository plantRepository;
    @Autowired private CareHistoryRepository careHistoryRepository;
    @Autowired private PlantAnniversarySentRepository sentRepository;

    @AfterEach
    void cleanup() {
        sentRepository.deleteAll();
        careHistoryRepository.deleteAll();
        plantRepository.deleteAll();
        locationRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("should_return_anniversary_when_today_is_year_birthday_in_user_zone")
    void should_return_anniversary_when_today_is_year_birthday_in_user_zone() {
        // arrange: юзер в Москве, растение заведено ровно год назад по календарю
        User user = savedUser(2001L, "Europe/Moscow");
        Plant plant = savedPlant(user, "Монстера", LocalDate.of(2025, 5, 24));
        // 5 поливов за прошлый год — должны попасть в Anniversary.waterings
        saveWaterings(plant, 5);

        // 2026-05-24 10:00 в Москве = 07:00 UTC
        Instant now = LocalDateTime.of(2026, 5, 24, 10, 0)
                .atZone(ZoneId.of("Europe/Moscow"))
                .toInstant();

        // act
        List<PlantAnniversaryService.Anniversary> due =
                anniversaryService.findDueAnniversaries(now);

        // assert
        assertThat(due).hasSize(1);
        PlantAnniversaryService.Anniversary a = due.get(0);
        assertThat(a.plantId()).isEqualTo(plant.getId());
        assertThat(a.plantName()).isEqualTo("Монстера");
        assertThat(a.yearsOld()).isEqualTo(1);
        assertThat(a.waterings()).isEqualTo(5);
        assertThat(a.anniversaryYear()).isEqualTo(2026);
        assertThat(a.buildText())
                .contains("Монстера")
                .contains("1 год")
                .contains("5 раз");
    }

    @Test
    @DisplayName("should_not_return_anniversary_when_plant_is_less_than_one_year_old")
    void should_not_return_anniversary_when_plant_is_less_than_one_year_old() {
        // arrange: растение заведено сегодня → 0 лет
        User user = savedUser(2002L, "Europe/Moscow");
        savedPlant(user, "Орхидея", LocalDate.of(2026, 5, 24));

        Instant now = LocalDateTime.of(2026, 5, 24, 10, 0)
                .atZone(ZoneId.of("Europe/Moscow"))
                .toInstant();

        // act
        List<PlantAnniversaryService.Anniversary> due =
                anniversaryService.findDueAnniversaries(now);

        // assert
        assertThat(due).isEmpty();
    }

    @Test
    @DisplayName("should_be_idempotent_when_marked_sent_for_same_year")
    void should_be_idempotent_when_marked_sent_for_same_year() {
        // arrange: годовщина наступила, шедулер «отправил» — markSent проставил запись
        User user = savedUser(2003L, "Europe/Moscow");
        Plant plant = savedPlant(user, "Фикус", LocalDate.of(2024, 5, 24));

        Instant now = LocalDateTime.of(2026, 5, 24, 10, 0)
                .atZone(ZoneId.of("Europe/Moscow"))
                .toInstant();

        // act 1 — первый запуск, годовщина в списке
        List<PlantAnniversaryService.Anniversary> firstRun =
                anniversaryService.findDueAnniversaries(now);
        assertThat(firstRun).hasSize(1);
        anniversaryService.markSent(plant.getId(), firstRun.get(0).anniversaryYear(), now);

        // act 2 — второй запуск того же шедулера в этот же день, годовщина уже не возвращается
        List<PlantAnniversaryService.Anniversary> secondRun =
                anniversaryService.findDueAnniversaries(now);

        // assert
        assertThat(secondRun).isEmpty();
        assertThat(sentRepository.findById(new PlantAnniversarySentId(plant.getId(), 2026)))
                .isPresent();
        assertThat(sentRepository.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("should_compute_anniversary_in_user_zone_when_utc_is_still_previous_day")
    void should_compute_anniversary_in_user_zone_when_utc_is_still_previous_day() {
        // arrange: юзер в Almaty (UTC+5). Шедулер запущен 2026-05-23 23:00 UTC —
        // в Алматы это уже 2026-05-24 04:00. Растение заведено год назад
        // (2025-05-24) → в TZ юзера сегодня годовщина.
        User user = savedUser(2004L, "Asia/Almaty");
        Plant plant = savedPlant(user, "Алое", LocalDate.of(2025, 5, 24));

        Instant nowUtc = LocalDateTime.of(2026, 5, 23, 23, 0)
                .toInstant(ZoneOffset.UTC);

        // sanity check: момент действительно «вчера» в UTC и «уже сегодня» в Almaty
        assertThat(nowUtc.atZone(ZoneOffset.UTC).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 5, 23));
        assertThat(nowUtc.atZone(ZoneId.of("Asia/Almaty")).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 5, 24));

        // act
        List<PlantAnniversaryService.Anniversary> due =
                anniversaryService.findDueAnniversaries(nowUtc);

        // assert: годовщина сработала именно потому, что мы считаем календарь
        // в TZ юзера, а не в UTC.
        assertThat(due).hasSize(1);
        assertThat(due.get(0).plantId()).isEqualTo(plant.getId());
        assertThat(due.get(0).yearsOld()).isEqualTo(1);
        assertThat(due.get(0).anniversaryYear()).isEqualTo(2026);
    }

    @Test
    @DisplayName("should_skip_archived_plants_even_on_anniversary_day")
    void should_skip_archived_plants_even_on_anniversary_day() {
        // arrange
        User user = savedUser(2005L, "Europe/Moscow");
        Plant plant = savedPlant(user, "Архивная монстера", LocalDate.of(2024, 5, 24));
        plant.setArchivedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        plantRepository.save(plant);

        Instant now = LocalDateTime.of(2026, 5, 24, 10, 0)
                .atZone(ZoneId.of("Europe/Moscow"))
                .toInstant();

        // act
        List<PlantAnniversaryService.Anniversary> due =
                anniversaryService.findDueAnniversaries(now);

        // assert
        assertThat(due).isEmpty();
    }

    @Test
    @DisplayName("should_skip_blocked_users")
    void should_skip_blocked_users() {
        // arrange
        User user = savedUser(2006L, "Europe/Moscow");
        user.setBlocked(true);
        userRepository.save(user);
        savedPlant(user, "Цветок заблокированного юзера", LocalDate.of(2024, 5, 24));

        Instant now = LocalDateTime.of(2026, 5, 24, 10, 0)
                .atZone(ZoneId.of("Europe/Moscow"))
                .toInstant();

        // act
        List<PlantAnniversaryService.Anniversary> due =
                anniversaryService.findDueAnniversaries(now);

        // assert
        assertThat(due).isEmpty();
    }

    @Test
    @DisplayName("should_return_empty_when_not_anniversary_day")
    void should_return_empty_when_not_anniversary_day() {
        // arrange: годовщина в мае, а сегодня июль
        User user = savedUser(2007L, "Europe/Moscow");
        savedPlant(user, "Не сегодня", LocalDate.of(2024, 5, 24));

        Instant now = LocalDateTime.of(2026, 7, 1, 10, 0)
                .atZone(ZoneId.of("Europe/Moscow"))
                .toInstant();

        // act
        List<PlantAnniversaryService.Anniversary> due =
                anniversaryService.findDueAnniversaries(now);

        // assert
        assertThat(due).isEmpty();
    }

    @Test
    @DisplayName("should_persist_marker_record_when_mark_sent_called")
    void should_persist_marker_record_when_mark_sent_called() {
        // arrange
        User user = savedUser(2008L, "Europe/Moscow");
        Plant plant = savedPlant(user, "Маркер", LocalDate.of(2024, 5, 24));
        Instant sentAt = Instant.parse("2026-05-24T07:05:00Z");

        // act
        anniversaryService.markSent(plant.getId(), 2026, sentAt);

        // assert
        PlantAnniversarySent stored = sentRepository
                .findById(new PlantAnniversarySentId(plant.getId(), 2026))
                .orElseThrow();
        assertThat(stored.getPlantId()).isEqualTo(plant.getId());
        assertThat(stored.getAnniversaryYear()).isEqualTo(2026);
        assertThat(stored.getSentAt()).isEqualTo(sentAt);
    }

    @Test
    @DisplayName("should_swallow_duplicate_when_mark_sent_called_twice")
    void should_swallow_duplicate_when_mark_sent_called_twice() {
        // arrange
        User user = savedUser(2009L, "Europe/Moscow");
        Plant plant = savedPlant(user, "Двойной маркер", LocalDate.of(2024, 5, 24));

        // act: второй вызов должен молча выйти, не падать DataIntegrityViolationException
        anniversaryService.markSent(plant.getId(), 2026, Instant.parse("2026-05-24T07:00:00Z"));
        anniversaryService.markSent(plant.getId(), 2026, Instant.parse("2026-05-24T08:00:00Z"));

        // assert: ровно одна запись в БД
        assertThat(sentRepository.count()).isEqualTo(1L);
    }

    // ===================== helpers =====================

    private User savedUser(Long chatId, String timezone) {
        return userRepository.save(User.builder()
                .telegramChatId(chatId)
                .timezone(timezone)
                .blocked(false)
                .build());
    }

    private Location savedDefaultLocation(User user) {
        return locationRepository.findByUserIdAndDefaultLocationTrue(user.getId())
                .orElseGet(() -> locationRepository.save(Location.builder()
                        .user(user)
                        .name(Location.DEFAULT_NAME)
                        .emoji(Location.DEFAULT_EMOJI)
                        .defaultLocation(true)
                        .build()));
    }

    private Plant savedPlant(User user, String name, LocalDate acquiredAt) {
        return plantRepository.save(Plant.builder()
                .user(user)
                .location(savedDefaultLocation(user))
                .name(name)
                .acquiredAt(acquiredAt)
                .build());
    }

    private void saveWaterings(Plant plant, int n) {
        for (int i = 0; i < n; i++) {
            careHistoryRepository.save(CareHistory.builder()
                    .plant(plant)
                    .taskType(TaskType.WATERING)
                    .doneAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).minusDays(i + 1))
                    .onTime(true)
                    .build());
        }
    }
}

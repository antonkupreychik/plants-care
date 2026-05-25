package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareHistory;
import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.HealthZone;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareHistoryRepository;
import com.plantcare.bot.repository.LocationRepository;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.repository.UserRepository;
import com.plantcare.bot.service.HealthScoreService.HealthScore;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Интеграционные тесты health-score (issue #138) на реальном Postgres через
 * Testcontainers — формула считается поверх настоящих {@code care_history}-записей
 * и repo-агрегатов on-time из #51, а не на моках репозитория.
 *
 * <p>Окно on-time-% — 30 дней в TZ юзера. Чтобы граница окна была детерминированной,
 * {@link Clock}-бин подменён фиксированным моментом ({@link #FIXED_NOW}) — все
 * {@code doneAt} в тестах расставляются относительно него.
 *
 * <p>Покрывает AC #138:
 * <ul>
 *   <li>happy — идеальная серия on-time + фото + заметки → ~100 и GREEN 🟢;</li>
 *   <li>happy — запущенное растение (низкий on-time, без бонусов) → низкий балл, RED 🔴;</li>
 *   <li>промежуточный on-time-% → YELLOW 🟡;</li>
 *   <li>вклад бонусов: фото/заметки поднимают балл на ожидаемую величину;</li>
 *   <li>edge — мало данных (&lt;3 действий) → insufficientData=true, балл не считаем;</li>
 *   <li>edge — не-UTC TZ (Asia/Almaty) на границе окна 30 дней: действие, попадающее
 *       в окно по локальному календарю, учитывается — а при UTC-расчёте выпало бы.</li>
 * </ul>
 */
@DisplayName("HealthScoreService — health-score растения (issue #138)")
@Import(HealthScoreServiceTest.FixedClockConfig.class)
class HealthScoreServiceTest extends IntegrationTestBase {

    /**
     * Фиксированный «сейчас». 00:30 UTC выбрано специально: для восточных TZ
     * (Almaty UTC+5) локальная дата уже «сегодня», а UTC-полночь окна и
     * локальная-полночь окна расходятся на день — это и проверяет TZ-кейс.
     */
    static final Instant FIXED_NOW = Instant.parse("2026-05-25T00:30:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @Autowired private HealthScoreService service;

    @Autowired private UserRepository userRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private PlantRepository plantRepository;
    @Autowired private CareHistoryRepository careHistoryRepository;

    @AfterEach
    void cleanup() {
        careHistoryRepository.deleteAll();
        plantRepository.deleteAll();
        locationRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("should_score_near_100_and_green_when_perfect_streak_with_photo_and_notes")
    void should_score_near_100_and_green_when_perfect_streak_with_photo_and_notes() {
        // arrange: 5 действий за окно, все on-time → on-time 100% → 80 баллов;
        // фото (+10) и заметки (+10) → ровно 100.
        User user = savedUser(8001L, "UTC");
        Plant plant = savedPlant(user, "Образцовый фикус", "photo-file-id-xyz", "поливаю по графику");
        for (int i = 1; i <= 5; i++) {
            saveCare(plant, TaskType.WATERING, daysAgoUtc(i), true);
        }

        // act
        HealthScore result = service.computeForPlant(plant);

        // assert
        assertSoftly(softly -> {
            softly.assertThat(result.insufficientData()).isFalse();
            softly.assertThat(result.score()).isEqualTo(100);
            softly.assertThat(result.zone()).isEqualTo(HealthZone.GREEN);
        });
    }

    @Test
    @DisplayName("should_score_low_and_red_when_neglected_plant_without_bonuses")
    void should_score_low_and_red_when_neglected_plant_without_bonuses() {
        // arrange: 4 действия за окно, лишь 1 on-time → on-time 25% → round(25*0.8)=20.
        // Ни фото, ни заметок → бонусов нет → score 20, ниже yellowThreshold(50) → RED.
        User user = savedUser(8002L, "UTC");
        Plant plant = savedPlant(user, "Запущенный кактус", null, null);
        saveCare(plant, TaskType.WATERING, daysAgoUtc(1), true);
        saveCare(plant, TaskType.WATERING, daysAgoUtc(2), false);
        saveCare(plant, TaskType.WATERING, daysAgoUtc(3), false);
        saveCare(plant, TaskType.WATERING, daysAgoUtc(4), false);

        // act
        HealthScore result = service.computeForPlant(plant);

        // assert
        assertSoftly(softly -> {
            softly.assertThat(result.insufficientData()).isFalse();
            softly.assertThat(result.score()).isEqualTo(20);
            softly.assertThat(result.zone()).isEqualTo(HealthZone.RED);
        });
    }

    @Test
    @DisplayName("should_be_yellow_when_on_time_is_moderate_without_bonuses")
    void should_be_yellow_when_on_time_is_moderate_without_bonuses() {
        // arrange: 4 действия, 3 on-time → 75% → round(75*0.8)=60. Бонусов нет.
        // 60 ∈ [yellow=50, green=80) → YELLOW.
        User user = savedUser(8003L, "UTC");
        Plant plant = savedPlant(user, "Средняя монстера", null, null);
        saveCare(plant, TaskType.WATERING, daysAgoUtc(1), true);
        saveCare(plant, TaskType.WATERING, daysAgoUtc(2), true);
        saveCare(plant, TaskType.WATERING, daysAgoUtc(3), true);
        saveCare(plant, TaskType.WATERING, daysAgoUtc(4), false);

        // act
        HealthScore result = service.computeForPlant(plant);

        // assert
        assertSoftly(softly -> {
            softly.assertThat(result.score()).isEqualTo(60);
            softly.assertThat(result.zone()).isEqualTo(HealthZone.YELLOW);
        });
    }

    @Test
    @DisplayName("should_add_twenty_points_when_photo_and_notes_present_vs_bare_plant")
    void should_add_twenty_points_when_photo_and_notes_present_vs_bare_plant() {
        // arrange: одинаковая on-time-история (3 on-time из 4 = 75% → база 60),
        // но одно растение без бонусов, другое — с фото и заметками (+10+10).
        User user = savedUser(8004L, "UTC");

        Plant bare = savedPlant(user, "Без бонусов", null, null);
        Plant decorated = savedPlant(user, "С фото и заметками", "file-id", "любимая");
        for (Plant p : new Plant[]{bare, decorated}) {
            saveCare(p, TaskType.WATERING, daysAgoUtc(1), true);
            saveCare(p, TaskType.WATERING, daysAgoUtc(2), true);
            saveCare(p, TaskType.WATERING, daysAgoUtc(3), true);
            saveCare(p, TaskType.WATERING, daysAgoUtc(4), false);
        }

        // act
        HealthScore bareScore = service.computeForPlant(bare);
        HealthScore decoratedScore = service.computeForPlant(decorated);

        // assert: бонусы дают ровно +20 (10 фото + 10 заметки).
        assertThat(decoratedScore.score() - bareScore.score()).isEqualTo(20);
        assertThat(bareScore.score()).isEqualTo(60);
        assertThat(decoratedScore.score()).isEqualTo(80);
    }

    @Test
    @DisplayName("should_return_insufficient_data_when_fewer_than_three_actions")
    void should_return_insufficient_data_when_fewer_than_three_actions() {
        // arrange: всего 2 активных действия (порог MIN_ACTIONS_FOR_STATS = 3).
        User user = savedUser(8005L, "UTC");
        Plant plant = savedPlant(user, "Новичок", "file-id", "заметка");
        saveCare(plant, TaskType.WATERING, daysAgoUtc(1), true);
        saveCare(plant, TaskType.WATERING, daysAgoUtc(2), true);

        // act
        HealthScore result = service.computeForPlant(plant);

        // assert: балл не считаем — состояние «мало данных», не падение.
        assertSoftly(softly -> {
            softly.assertThat(result.insufficientData()).isTrue();
            softly.assertThat(result.score()).isZero();
            softly.assertThat(result.zone()).isNull();
        });
    }

    @Test
    @DisplayName("should_count_action_on_window_edge_in_user_timezone_not_utc")
    void should_count_action_on_window_edge_in_user_timezone_not_utc() {
        // arrange: юзер в Asia/Almaty (UTC+5). Фиксированный «сейчас» = 2026-05-25 00:30 UTC,
        // что локально = 2026-05-25 05:30 Алматы → сегодня (локально) = 25 мая.
        // Окно 30 дней в TZ юзера → начало дня 25 апреля по Алматы:
        //   2026-04-25 00:00 +05:00 = 2026-04-24 19:00 UTC  → это граница `since`.
        // При наивном UTC-расчёте границей было бы 2026-04-25 00:00 UTC.
        //
        // Граничное действие: 2026-04-24 20:00 UTC = 2026-04-25 01:00 Алматы.
        //   - по локальному календарю Алматы оно ВНУТРИ последних 30 дней (после since);
        //   - по UTC-расчёту (since=2026-04-25 00:00 UTC) оно бы ВЫПАЛО из окна.
        // Делаем его on-time, а все «глубокие» действия в окне — late, чтобы включение
        // граничного действия сдвинуло on-time-% (и зону) заметно.
        User user = savedUser(8006L, "Asia/Almaty");
        Plant plant = savedPlant(user, "Алматинское граничное", null, null);

        // Граничное on-time действие — попадает в окно только при TZ-расчёте.
        saveCare(plant, TaskType.WATERING, Instant.parse("2026-04-24T20:00:00Z"), true);
        // Три глубоко-внутри-окна late действия (никаких споров о границе).
        saveCare(plant, TaskType.WATERING, daysAgoUtc(2), false);
        saveCare(plant, TaskType.WATERING, daysAgoUtc(3), false);
        saveCare(plant, TaskType.WATERING, daysAgoUtc(4), false);

        // act
        HealthScore result = service.computeForPlant(plant);

        // assert: окно содержит 4 действия, 1 on-time → 25% → round(25*0.8)=20.
        // Если бы границу считали в UTC, граничное действие выпало бы → 3 действия,
        // 0 on-time → 0%. Балл 20 (а не 0) доказывает учёт действия в окне по TZ юзера.
        assertSoftly(softly -> {
            softly.assertThat(result.insufficientData()).isFalse();
            softly.assertThat(result.score()).isEqualTo(20);
            softly.assertThat(result.zone()).isEqualTo(HealthZone.RED);
        });
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

    private Plant savedPlant(User user, String name, String photoFileId, String notes) {
        return plantRepository.save(Plant.builder()
                .user(user)
                .location(savedDefaultLocation(user))
                .name(name)
                .photoFileId(photoFileId)
                .notes(notes)
                .acquiredAt(LocalDate.of(2024, 1, 1))
                .build());
    }

    /** doneAt хранится в UTC; конвертим момент во времени в UTC LocalDateTime. */
    private CareHistory saveCare(Plant plant, TaskType type, Instant instant, boolean onTime) {
        LocalDateTime doneAtUtc = instant.atZone(ZoneOffset.UTC)
                .toLocalDateTime().truncatedTo(ChronoUnit.MICROS);
        return careHistoryRepository.save(CareHistory.builder()
                .plant(plant)
                .taskType(type)
                .doneAt(doneAtUtc)
                .onTime(onTime)
                .build());
    }

    /** Момент «N суток назад» относительно фиксированного {@link #FIXED_NOW}. */
    private Instant daysAgoUtc(int days) {
        return FIXED_NOW.minus(days, ChronoUnit.DAYS);
    }
}

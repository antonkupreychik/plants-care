package com.plantcare.core.service;

import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.LocationRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.core.service.PlantDiagnosisReportService.DiagnosisReport;
import com.plantcare.core.service.PlantDiagnosisReportService.Issue;
import com.plantcare.core.service.PlantDiagnosisReportService.Severity;
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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Интеграционные тесты пассивной диагностики (issue #193) на реальном Postgres
 * через Testcontainers — диагноз строится поверх настоящих {@code care_schedules}
 * и {@code care_history}-записей, а не на моках репозиториев (правило проекта:
 * моки только для внешних API).
 *
 * <p>{@link Clock}-бин подменён фиксированным моментом ({@link #FIXED_NOW}), чтобы
 * «сегодня» и «на сколько дней просрочено» были детерминированными. Просрочка
 * считается в TZ юзера как разница календарных дней, поэтому {@code nextDueAt}
 * выставляется относительно зафиксированного «сейчас».
 *
 * <p>Покрывает правила #193:
 * <ul>
 *   <li>happy — полив просрочен ≤ интервала → UNDERWATERED/MEDIUM «Нужен полив»;</li>
 *   <li>полив просрочен &gt; интервала → UNDERWATERED/HIGH «Пересушен», рекомендации deduped;</li>
 *   <li>несколько типов задач → сортировка severity desc (HIGH первым), recommendations deduped/ordered;</li>
 *   <li>health RED-зона без просрочек → NEGLECTED/MEDIUM;</li>
 *   <li>edge — мало данных (&lt;3 действий) → пустые issues + «мало данных»-подсказка;</li>
 *   <li>edge — здоровое растение → пустые issues + пустые recommendations;</li>
 *   <li>edge — не-UTC TZ (Asia/Almaty) на границе суток: просрочка считается в TZ юзера;</li>
 *   <li>edge — acclimation активна → диагноз строится как обычно (acclimation игнорируется).</li>
 * </ul>
 */
@DisplayName("PlantDiagnosisReportService — пассивная диагностика (issue #193)")
@Import(PlantDiagnosisReportServiceTest.FixedClockConfig.class)
class PlantDiagnosisReportServiceTest extends IntegrationTestBase {

    /**
     * Фиксированный «сейчас». 00:30 UTC специально: для восточных TZ (Almaty UTC+5)
     * локальная дата уже «сегодня», а UTC-полночь и локальная-полночь расходятся на
     * день — это и эксплуатирует TZ-кейс.
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

    @Autowired private PlantDiagnosisReportService service;

    @Autowired private UserRepository userRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private PlantRepository plantRepository;
    @Autowired private CareHistoryRepository careHistoryRepository;
    @Autowired private CareScheduleRepository careScheduleRepository;

    @AfterEach
    void cleanup() {
        careHistoryRepository.deleteAll();
        careScheduleRepository.deleteAll();
        plantRepository.deleteAll();
        locationRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("should_report_underwatered_medium_when_watering_overdue_within_one_interval")
    void should_report_underwatered_medium_when_watering_overdue_within_one_interval() {
        // arrange: интервал 7 дней, просрочка 3 дня (<= интервала) → «Нужен полив» (MEDIUM).
        User user = savedUser(9001L, "UTC");
        Plant plant = savedPlant(user, "Фикус", null, null);
        givenEnoughHistory(plant);
        saveSchedule(plant, TaskType.WATERING, 7, daysAgo(3), true);

        // act
        DiagnosisReport report = service.diagnose(plant);

        // assert
        assertThat(report.issues()).containsExactly(
                new Issue("UNDERWATERED", Severity.MEDIUM, "Нужен полив",
                        report.issues().get(0).recommendations()));
        assertThat(report.issues().get(0).recommendations()).containsExactly("Полей растение сегодня");
        assertThat(report.recommendations()).containsExactly("Полей растение сегодня");
    }

    @Test
    @DisplayName("should_report_underwatered_high_and_dedup_recommendations_when_overdue_more_than_one_interval")
    void should_report_underwatered_high_and_dedup_recommendations_when_overdue_more_than_one_interval() {
        // arrange: интервал 5 дней, просрочка 12 дней (> интервала) → «Пересушен» (HIGH).
        User user = savedUser(9002L, "UTC");
        Plant plant = savedPlant(user, "Пересушенный кактус", null, null);
        givenEnoughHistory(plant);
        saveSchedule(plant, TaskType.WATERING, 5, daysAgo(12), true);

        // act
        DiagnosisReport report = service.diagnose(plant);

        // assert
        assertThat(report.issues()).hasSize(1);
        Issue issue = report.issues().get(0);
        assertSoftly(softly -> {
            softly.assertThat(issue.code()).isEqualTo("UNDERWATERED");
            softly.assertThat(issue.severity()).isEqualTo(Severity.HIGH);
            softly.assertThat(issue.title()).isEqualTo("Пересушен");
        });
        assertThat(report.recommendations())
                .containsExactly("Полей растение сегодня", "Проверь, не пересох ли грунт");
    }

    @Test
    @DisplayName("should_sort_issues_high_first_and_dedup_recommendations_when_multiple_overdue_tasks")
    void should_sort_issues_high_first_and_dedup_recommendations_when_multiple_overdue_tasks() {
        // arrange: полив просрочен > интервала (HIGH), подкормка и опрыскивание просрочены (LOW).
        User user = savedUser(9003L, "UTC");
        Plant plant = savedPlant(user, "Запущенная монстера", null, null);
        givenEnoughHistory(plant);
        saveSchedule(plant, TaskType.WATERING, 5, daysAgo(12), true);
        saveSchedule(plant, TaskType.FERTILIZING, 14, daysAgo(2), true);
        saveSchedule(plant, TaskType.MISTING, 3, daysAgo(2), true);

        // act
        DiagnosisReport report = service.diagnose(plant);

        // assert: HIGH (полив) первым, затем LOW по порядку типов (MISTING перед FERTILIZING).
        assertThat(report.issues())
                .extracting(Issue::code)
                .containsExactly("UNDERWATERED", "LOW_HUMIDITY", "UNDERFED");
        assertThat(report.issues())
                .extracting(Issue::severity)
                .containsExactly(Severity.HIGH, Severity.LOW, Severity.LOW);
        assertThat(report.recommendations()).containsExactly(
                "Полей растение сегодня",
                "Проверь, не пересох ли грунт",
                "Опрыскай растение",
                "Подкорми растение по графику");
    }

    @Test
    @DisplayName("should_report_neglected_medium_when_health_zone_is_red_and_no_overdue_schedule")
    void should_report_neglected_medium_when_health_zone_is_red_and_no_overdue_schedule() {
        // arrange: 4 действия, лишь 1 on-time → 25% → score 20 → RED-зона. Расписаний нет.
        User user = savedUser(9004L, "UTC");
        Plant plant = savedPlant(user, "Нерегулярный уход", null, null);
        saveCare(plant, TaskType.WATERING, daysAgo(1), true);
        saveCare(plant, TaskType.WATERING, daysAgo(2), false);
        saveCare(plant, TaskType.WATERING, daysAgo(3), false);
        saveCare(plant, TaskType.WATERING, daysAgo(4), false);

        // act
        DiagnosisReport report = service.diagnose(plant);

        // assert
        assertThat(report.issues()).containsExactly(
                new Issue("NEGLECTED", Severity.MEDIUM, "Уход нерегулярный",
                        report.issues().get(0).recommendations()));
        assertThat(report.recommendations()).containsExactly("Вернись к регулярному графику ухода");
    }

    @Test
    @DisplayName("should_return_insufficient_data_hint_when_fewer_than_three_actions")
    void should_return_insufficient_data_hint_when_fewer_than_three_actions() {
        // arrange: всего 2 активных действия (порог MIN_ACTIONS_FOR_STATS = 3),
        // при этом расписание просрочено — но диагноз не строим из-за нехватки данных.
        User user = savedUser(9005L, "UTC");
        Plant plant = savedPlant(user, "Новичок", null, null);
        saveCare(plant, TaskType.WATERING, daysAgo(1), true);
        saveCare(plant, TaskType.WATERING, daysAgo(2), true);
        saveSchedule(plant, TaskType.WATERING, 5, daysAgo(20), true);

        // act
        DiagnosisReport report = service.diagnose(plant);

        // assert
        assertThat(report.issues()).isEmpty();
        assertThat(report.recommendations())
                .containsExactly("Пока мало данных для диагноза — продолжай отмечать уход");
    }

    @Test
    @DisplayName("should_return_empty_report_when_plant_is_healthy")
    void should_return_empty_report_when_plant_is_healthy() {
        // arrange: 5 действий, все on-time → 100% → GREEN, расписание НЕ просрочено
        // (nextDueAt в будущем) → ни одной проблемы.
        User user = savedUser(9006L, "UTC");
        Plant plant = savedPlant(user, "Образцовый фикус", "photo-id", "поливаю по графику");
        for (int i = 1; i <= 5; i++) {
            saveCare(plant, TaskType.WATERING, daysAgo(i), true);
        }
        saveSchedule(plant, TaskType.WATERING, 7, inDays(5), true);

        // act
        DiagnosisReport report = service.diagnose(plant);

        // assert
        assertThat(report.issues()).isEmpty();
        assertThat(report.recommendations()).isEmpty();
    }

    @Test
    @DisplayName("should_compute_days_overdue_in_user_timezone_not_utc")
    void should_compute_days_overdue_in_user_timezone_not_utc() {
        // arrange: юзер в Asia/Almaty (UTC+5). FIXED_NOW = 2026-05-25 00:30 UTC =
        // 2026-05-25 05:30 Алматы → сегодня (локально) = 25 мая.
        //
        // nextDueAt хранится как UTC wall-clock. Возьмём nextDueAt = 2026-05-24 22:00.
        //   - как Instant(UTC) → 2026-05-24 22:00Z = 2026-05-25 03:00 Алматы → dueDate = 25 мая;
        //   - значит daysOverdue = today(25) - due(25) = 0 → НЕ просрочено.
        // При наивном UTC-расчёте dueDate было бы 24 мая → daysOverdue = 1 → ложная просрочка.
        // Пустой список issues доказывает, что граница суток считается в TZ юзера.
        User user = savedUser(9007L, "Asia/Almaty");
        Plant plant = savedPlant(user, "Алматинское граничное", null, null);
        givenEnoughHistory(plant);
        saveSchedule(plant, TaskType.WATERING, 7,
                LocalDateTime.parse("2026-05-24T22:00:00"), true);

        // act
        DiagnosisReport report = service.diagnose(plant);

        // assert: due-дата в Алматы = сегодня → нет просрочки → нет проблем.
        assertThat(report.issues()).isEmpty();
    }

    @Test
    @DisplayName("should_diagnose_normally_when_acclimation_active")
    void should_diagnose_normally_when_acclimation_active() {
        // arrange: растение в acclimation-режиме (acclimationUntil в будущем). Acclimation
        // не должна подавлять диагноз — просроченный полив всё равно даёт проблему.
        User user = savedUser(9008L, "UTC");
        // acclimationUntil выставляем в одном save: повторный save() детачнутого plant
        // сделал бы merge и подменил user lazy-прокси → LazyInit вне транзакции теста.
        Plant plant = plantRepository.save(Plant.builder()
                .user(user)
                .location(savedDefaultLocation(user))
                .name("На адаптации")
                .acquiredAt(LocalDate.of(2024, 1, 1))
                .acclimationUntil(FIXED_NOW.plus(10, ChronoUnit.DAYS)
                        .atZone(ZoneOffset.UTC).toLocalDateTime())
                .build());
        givenEnoughHistory(plant);
        saveSchedule(plant, TaskType.WATERING, 7, daysAgo(3), true);

        // act
        DiagnosisReport report = service.diagnose(plant);

        // assert: acclimation проигнорирована — полив всё равно просрочен.
        assertThat(report.issues())
                .extracting(Issue::code)
                .containsExactly("UNDERWATERED");
        assertThat(report.issues().get(0).severity()).isEqualTo(Severity.MEDIUM);
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

    /**
     * Достаточно активной истории, чтобы пройти порог MIN_ACTIONS_FOR_STATS (3) и при
     * этом не свалиться в RED-зону: 3 on-time действия → 100% → GREEN. Так health-сигнал
     * не зашумляет тесты, проверяющие сигнал «просроченное расписание».
     */
    private void givenEnoughHistory(Plant plant) {
        saveCare(plant, TaskType.WATERING, daysAgo(1), true);
        saveCare(plant, TaskType.WATERING, daysAgo(2), true);
        saveCare(plant, TaskType.WATERING, daysAgo(3), true);
    }

    /** doneAt хранится как UTC wall-clock LocalDateTime. */
    private CareHistory saveCare(Plant plant, TaskType type, LocalDateTime doneAtUtc, boolean onTime) {
        return careHistoryRepository.save(CareHistory.builder()
                .plant(plant)
                .taskType(type)
                .doneAt(doneAtUtc.truncatedTo(ChronoUnit.MICROS))
                .onTime(onTime)
                .build());
    }

    private CareSchedule saveSchedule(Plant plant, TaskType type, int intervalDays,
                                      LocalDateTime nextDueAtUtc, boolean active) {
        return careScheduleRepository.save(CareSchedule.builder()
                .plant(plant)
                .taskType(type)
                .intervalDays(intervalDays)
                .nextDueAt(nextDueAtUtc)
                .active(active)
                .build());
    }

    /** UTC wall-clock «N суток назад» относительно фиксированного {@link #FIXED_NOW}. */
    private LocalDateTime daysAgo(int days) {
        return FIXED_NOW.minus(days, ChronoUnit.DAYS)
                .atZone(ZoneOffset.UTC).toLocalDateTime();
    }

    /** UTC wall-clock «через N суток» относительно фиксированного {@link #FIXED_NOW}. */
    private LocalDateTime inDays(int days) {
        return FIXED_NOW.plus(days, ChronoUnit.DAYS)
                .atZone(ZoneOffset.UTC).toLocalDateTime();
    }
}

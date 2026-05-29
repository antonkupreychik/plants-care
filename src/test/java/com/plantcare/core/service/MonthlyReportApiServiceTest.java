package com.plantcare.core.service;

import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.LocationRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.core.service.MonthlyReportApiService.MonthlyReport;
import com.plantcare.core.service.MonthlyReportApiService.WeeklyHealthBucket;
import com.plantcare.bot.support.IntegrationTestBase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Интеграционные тесты REST-сервиса месячного отчёта (issue #192, gap G23) на
 * реальном Postgres через Testcontainers — агрегаты {@code COUNT}/{@code GROUP BY}
 * и проекция {@code (done_at, was_on_time)} прогоняются на Postgres-диалекте, а не
 * на моках репозитория (CLAUDE.md: «Не мокать репозитории, если есть Testcontainers»).
 *
 * <p>Границы месяца считаются в TZ юзера и конвертируются в UTC — отдельный тест
 * проверяет, что действие у границы месяца попадает в нужный месяц именно по TZ
 * юзера, а не по UTC-календарю.
 *
 * <p>Покрывает:
 *   - happy path: done/overdue/byType/healthTrend на реальных данных;
 *   - не-UTC граница месяца (Asia/Almaty) — действие у полуночи месяца;
 *   - ISO-неделя на стыке годов + округление onTimePct до 2 знаков;
 *   - пустой месяц → нули и пустой тренд;
 *   - стабильность byType (все 4 ключа даже при одном типе);
 *   - изоляция по пользователю (чужие данные не текут);
 *   - невалидный/пустой month → IllegalArgumentException;
 *   - cancelled-записи не считаются.
 */
@DisplayName("MonthlyReportApiService — REST месячный отчёт (issue #192)")
class MonthlyReportApiServiceTest extends IntegrationTestBase {

    @Autowired private MonthlyReportApiService service;

    @Autowired private UserRepository userRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private PlantRepository plantRepository;
    @Autowired private CareHistoryRepository careHistoryRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    /**
     * Жёсткая очистка ДО и ПОСЛЕ каждого теста через {@code TRUNCATE ... CASCADE}.
     *
     * <p>Контейнер Postgres переиспользуется ({@code withReuse}) между запусками
     * {@code mvn}, поэтому в нём могут оставаться строки прошлых прогонов в любых
     * дочерних таблицах (care_schedules, reminders и т.п.). Обычный
     * {@code deleteAll()} по 4 таблицам падал бы на FK от неучтённых детей —
     * {@code TRUNCATE users CASCADE} атомарно сносит весь граф зависимостей и
     * делает тест независимым от остаточных данных (известная контаминация).
     *
     * <p>Транзакция нужна явно через {@link TransactionTemplate}: {@code @Transactional}
     * на lifecycle-методе JUnit Spring не оборачивает.
     */
    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery(
                        "TRUNCATE TABLE users RESTART IDENTITY CASCADE").executeUpdate());
    }

    @Test
    @DisplayName("should_aggregate_done_overdue_byType_and_trend_when_user_active_in_month")
    void should_aggregate_done_overdue_byType_and_trend_when_user_active_in_month() {
        // arrange: апрель 2026, UTC. 5 активных + 1 cancelled. 2 из активных — late.
        // Действия распределены по двум ISO-неделям апреля.
        User user = savedUser(7001L, "UTC");
        Plant plant = savedPlant(user, "Монстера");

        // ISO-неделя 2026-W15 (06–12 апр): WATERING on-time, MISTING late
        savedCare(plant, TaskType.WATERING, utc("2026-04-06T10:00:00"), true, null);
        savedCare(plant, TaskType.MISTING, utc("2026-04-08T10:00:00"), false, null);
        // ISO-неделя 2026-W16 (13–19 апр): WATERING on-time, FERTILIZING late, SOIL_CHECK on-time
        savedCare(plant, TaskType.WATERING, utc("2026-04-13T10:00:00"), true, null);
        savedCare(plant, TaskType.FERTILIZING, utc("2026-04-15T10:00:00"), false, null);
        savedCare(plant, TaskType.SOIL_CHECK, utc("2026-04-17T10:00:00"), true, null);
        // cancelled запись в апреле — не должна попасть ни в один агрегат
        CareHistory compensator = savedCare(plant, TaskType.WATERING, utc("2026-04-18T10:00:00"), true, null);
        savedCare(plant, TaskType.WATERING, utc("2026-04-10T10:00:00"), true, compensator);

        // act
        MonthlyReport report = service.getMonthlyReport(user.getId(), "UTC", "2026-04");

        // assert
        assertSoftly(softly -> {
            softly.assertThat(report.month()).isEqualTo("2026-04");
            softly.assertThat(report.done()).isEqualTo(6L); // 5 базовых + compensator, cancelled не в счёт
            softly.assertThat(report.overdue()).isEqualTo(2L); // MISTING + FERTILIZING late
            softly.assertThat(report.byType())
                    .containsEntry(TaskType.WATERING, 3L)
                    .containsEntry(TaskType.MISTING, 1L)
                    .containsEntry(TaskType.FERTILIZING, 1L)
                    .containsEntry(TaskType.SOIL_CHECK, 1L);
            // тренд: две недели, хронологически
            softly.assertThat(report.healthTrend())
                    .extracting(WeeklyHealthBucket::week)
                    .containsExactly("2026-W15", "2026-W16");
        });
    }

    @Test
    @DisplayName("should_count_action_in_local_month_when_done_near_boundary_in_almaty")
    void should_count_action_in_local_month_when_done_near_boundary_in_almaty() {
        // arrange: юзер в Asia/Almaty (UTC+5). Действие 1 апреля 02:00 ЛОКАЛЬНО Алматы
        // = 31 марта 21:00 UTC. По UTC-календарю это МАРТ, по локальному — АПРЕЛЬ.
        // Оно ОБЯЗАНО учитываться в апрельском отчёте (границы — в TZ юзера).
        // Зеркально: действие 1 мая 02:00 локально = 30 апр 21:00 UTC (UTC-апрель!),
        // но локально это МАЙ → НЕ должно попасть в апрель.
        User user = savedUser(7002L, "Asia/Almaty");
        Plant plant = savedPlant(user, "Алматинская");

        savedCare(plant, TaskType.WATERING, utc("2026-03-31T21:00:00"), true, null);  // апрель локально
        savedCare(plant, TaskType.WATERING, utc("2026-04-15T07:00:00"), true, null);  // явно апрель
        savedCare(plant, TaskType.WATERING, utc("2026-04-30T21:00:00"), true, null);  // май локально (1 мая 02:00)

        // act
        MonthlyReport report = service.getMonthlyReport(user.getId(), "Asia/Almaty", "2026-04");

        // assert: граничные считаются по TZ юзера → ровно 2 (31 марта 21:00 UTC + 15 апр),
        // а 30 апр 21:00 UTC (= 1 мая локально) выпадает. При наивном UTC-расчёте было бы
        // тоже 2, но другой состав — поэтому проверяем зеркало в отдельном assert ниже.
        assertThat(report.done()).isEqualTo(2L);

        // act 2: тот же набор за МАЙ — должно быть ровно 1 (то, что выпало из апреля)
        MonthlyReport may = service.getMonthlyReport(user.getId(), "Asia/Almaty", "2026-05");

        // assert: майское действие (1 мая 02:00 локально) учтено в мае
        assertThat(may.done()).isEqualTo(1L);
    }

    @Test
    @DisplayName("should_bucket_trend_by_user_timezone_not_utc_on_week_boundary")
    void should_bucket_trend_by_user_timezone_not_utc_on_week_boundary() {
        // arrange: Asia/Almaty. Действие 2026-04-06 01:00 ЛОКАЛЬНО (понедельник, ISO-W15)
        // = 2026-04-05 20:00 UTC (воскресенье, ISO-W14). Бакетинг идёт в TZ юзера →
        // должно лечь в W15, а не W14. Второе действие явно в W15 для контроля.
        User user = savedUser(7003L, "Asia/Almaty");
        Plant plant = savedPlant(user, "Граничная неделя");

        savedCare(plant, TaskType.WATERING, utc("2026-04-05T20:00:00"), true, null); // пн W15 локально
        savedCare(plant, TaskType.WATERING, utc("2026-04-07T07:00:00"), true, null); // вт W15 локально

        // act
        MonthlyReport report = service.getMonthlyReport(user.getId(), "Asia/Almaty", "2026-04");

        // assert: оба в одной неделе W15 (если бы бакетили по UTC, первое уехало бы в W14)
        assertThat(report.healthTrend())
                .extracting(WeeklyHealthBucket::week)
                .containsExactly("2026-W15");
        assertThat(report.healthTrend().get(0).done()).isEqualTo(2L);
    }

    @Test
    @DisplayName("should_round_onTimePct_to_two_decimals_per_week")
    void should_round_onTimePct_to_two_decimals_per_week() {
        // arrange: одна ISO-неделя, 3 действия, 2 on-time → 2/3 = 0.6667 → округление 0.67
        User user = savedUser(7004L, "UTC");
        Plant plant = savedPlant(user, "Дробь");

        savedCare(plant, TaskType.WATERING, utc("2026-04-13T10:00:00"), true, null);
        savedCare(plant, TaskType.WATERING, utc("2026-04-14T10:00:00"), true, null);
        savedCare(plant, TaskType.WATERING, utc("2026-04-15T10:00:00"), false, null);

        // act
        MonthlyReport report = service.getMonthlyReport(user.getId(), "UTC", "2026-04");

        // assert
        assertThat(report.healthTrend()).hasSize(1);
        WeeklyHealthBucket bucket = report.healthTrend().get(0);
        assertThat(bucket.week()).isEqualTo("2026-W16");
        assertThat(bucket.done()).isEqualTo(3L);
        assertThat(bucket.onTimePct()).isEqualTo(0.67);
    }

    @Test
    @DisplayName("should_order_trend_chronologically_across_year_boundary")
    void should_order_trend_chronologically_across_year_boundary() {
        // arrange: январь 2026. ISO-W01 2026 начинается 2025-12-29 (пн). Действие
        // 2025-12-29 (которое в запросе за январь не появится — оно вне окна месяца),
        // поэтому возьмём действия внутри января, попадающие на W01 (2025-W01? нет —
        // 2026-W01) и W02 2026 — и проверим хронологический порядок ярлыков с week-year.
        // Главная цель: метки идут возрастающе и week-based-year корректно 2026.
        User user = savedUser(7005L, "UTC");
        Plant plant = savedPlant(user, "Январь");

        // 2026-01-02 — пятница ISO-W01-2026
        savedCare(plant, TaskType.WATERING, utc("2026-01-02T10:00:00"), true, null);
        // 2026-01-08 — четверг ISO-W02-2026
        savedCare(plant, TaskType.WATERING, utc("2026-01-08T10:00:00"), true, null);

        // act
        MonthlyReport report = service.getMonthlyReport(user.getId(), "UTC", "2026-01");

        // assert: метки в хронологическом порядке, week-based-year = 2026
        assertThat(report.healthTrend())
                .extracting(WeeklyHealthBucket::week)
                .containsExactly("2026-W01", "2026-W02");
    }

    @Test
    @DisplayName("should_return_zeros_and_empty_trend_when_no_history_in_month")
    void should_return_zeros_and_empty_trend_when_no_history_in_month() {
        // arrange: растение есть, но за апрель ноль действий
        User user = savedUser(7006L, "UTC");
        savedPlant(user, "Молчун");

        // act
        MonthlyReport report = service.getMonthlyReport(user.getId(), "UTC", "2026-04");

        // assert
        assertSoftly(softly -> {
            softly.assertThat(report.done()).isZero();
            softly.assertThat(report.overdue()).isZero();
            softly.assertThat(report.byType())
                    .containsEntry(TaskType.WATERING, 0L)
                    .containsEntry(TaskType.MISTING, 0L)
                    .containsEntry(TaskType.FERTILIZING, 0L)
                    .containsEntry(TaskType.SOIL_CHECK, 0L);
            softly.assertThat(report.streak()).isZero(); // нет истории → стрик 0
            softly.assertThat(report.healthTrend()).isEmpty();
        });
    }

    @Test
    @DisplayName("should_return_all_four_byType_keys_when_only_watering_present")
    void should_return_all_four_byType_keys_when_only_watering_present() {
        // arrange: за месяц только WATERING
        User user = savedUser(7007L, "UTC");
        Plant plant = savedPlant(user, "Только полив");
        savedCare(plant, TaskType.WATERING, utc("2026-04-10T10:00:00"), true, null);
        savedCare(plant, TaskType.WATERING, utc("2026-04-20T10:00:00"), true, null);

        // act
        MonthlyReport report = service.getMonthlyReport(user.getId(), "UTC", "2026-04");

        // assert: все 4 ключа, остальные = 0
        assertThat(report.byType())
                .containsOnlyKeys(TaskType.WATERING, TaskType.MISTING,
                        TaskType.FERTILIZING, TaskType.SOIL_CHECK)
                .containsEntry(TaskType.WATERING, 2L)
                .containsEntry(TaskType.MISTING, 0L)
                .containsEntry(TaskType.FERTILIZING, 0L)
                .containsEntry(TaskType.SOIL_CHECK, 0L);
    }

    @Test
    @DisplayName("should_not_count_other_users_data_in_report")
    void should_not_count_other_users_data_in_report() {
        // arrange: два юзера, у каждого активность в апреле. Отчёт строится для первого.
        User me = savedUser(7008L, "UTC");
        Plant myPlant = savedPlant(me, "Моё");
        savedCare(myPlant, TaskType.WATERING, utc("2026-04-10T10:00:00"), true, null);

        User other = savedUser(7009L, "UTC");
        Plant otherPlant = savedPlant(other, "Чужое");
        savedCare(otherPlant, TaskType.WATERING, utc("2026-04-11T10:00:00"), true, null);
        savedCare(otherPlant, TaskType.MISTING, utc("2026-04-12T10:00:00"), false, null);

        // act
        MonthlyReport report = service.getMonthlyReport(me.getId(), "UTC", "2026-04");

        // assert: только моё одно действие, чужие два не утекли
        assertSoftly(softly -> {
            softly.assertThat(report.done()).isEqualTo(1L);
            softly.assertThat(report.overdue()).isZero();
            softly.assertThat(report.byType()).containsEntry(TaskType.WATERING, 1L);
            softly.assertThat(report.healthTrend()).hasSize(1);
            softly.assertThat(report.healthTrend().get(0).done()).isEqualTo(1L);
        });
    }

    @Test
    @DisplayName("should_compute_current_streak_when_consecutive_active_days_until_today")
    void should_compute_current_streak_when_consecutive_active_days_until_today() {
        // arrange: стрик считается от LocalDate.now(tz). Привязываем действия к
        // «сегодня», «вчера», «позавчера» в UTC, чтобы тест не зависел от стенового
        // времени машины. Запрашиваем отчёт за текущий месяц.
        User user = savedUser(7010L, "UTC");
        Plant plant = savedPlant(user, "Стрик");

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        saveCareOn(plant, today, 10);
        saveCareOn(plant, today.minusDays(1), 10);
        saveCareOn(plant, today.minusDays(2), 10);
        // разрыв: 4 дня назад (не подряд) — не должен продлевать стрик
        saveCareOn(plant, today.minusDays(4), 10);

        String month = today.toString().substring(0, 7); // YYYY-MM текущего месяца

        // act
        MonthlyReport report = service.getMonthlyReport(user.getId(), "UTC", month);

        // assert: 3 подряд (today, -1, -2), разрыв на -3 → стрик 3
        assertThat(report.streak()).isEqualTo(3);
    }

    @Test
    @DisplayName("should_throw_when_month_is_blank")
    void should_throw_when_month_is_blank() {
        // arrange
        User user = savedUser(7011L, "UTC");

        // act + assert
        assertThatThrownBy(() -> service.getMonthlyReport(user.getId(), "UTC", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("month");
    }

    @Test
    @DisplayName("should_throw_when_month_has_invalid_value")
    void should_throw_when_month_has_invalid_value() {
        // arrange
        User user = savedUser(7012L, "UTC");

        // act + assert: 13-й месяц не парсится в YearMonth
        assertThatThrownBy(() -> service.getMonthlyReport(user.getId(), "UTC", "2026-13"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2026-13");
    }

    @Test
    @DisplayName("should_throw_when_month_is_garbage")
    void should_throw_when_month_is_garbage() {
        // arrange
        User user = savedUser(7013L, "UTC");

        // act + assert
        assertThatThrownBy(() -> service.getMonthlyReport(user.getId(), "UTC", "garbage"))
                .isInstanceOf(IllegalArgumentException.class);
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

    private Plant savedPlant(User user, String name) {
        return plantRepository.save(Plant.builder()
                .user(user)
                .location(savedDefaultLocation(user))
                .name(name)
                .acquiredAt(LocalDate.of(2024, 1, 1))
                .build());
    }

    /** done_at хранится в БД как UTC LocalDateTime — передаём его напрямую. */
    private CareHistory savedCare(Plant plant, TaskType type, LocalDateTime doneAtUtc,
                                  boolean onTime, CareHistory cancelledBy) {
        return careHistoryRepository.save(CareHistory.builder()
                .plant(plant)
                .taskType(type)
                .doneAt(doneAtUtc.truncatedTo(ChronoUnit.MICROS))
                .onTime(onTime)
                .cancelledBy(cancelledBy)
                .build());
    }

    private void saveCareOn(Plant plant, LocalDate dayUtc, int hour) {
        savedCare(plant, TaskType.WATERING, dayUtc.atTime(hour, 0), true, null);
    }

    private static LocalDateTime utc(String isoLocal) {
        return LocalDateTime.parse(isoLocal).truncatedTo(ChronoUnit.MICROS);
    }
}

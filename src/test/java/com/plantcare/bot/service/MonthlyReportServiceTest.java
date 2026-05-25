package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareHistory;
import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.MonthlyReportSent;
import com.plantcare.bot.domain.MonthlyReportSentId;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareHistoryRepository;
import com.plantcare.bot.repository.LocationRepository;
import com.plantcare.bot.repository.MonthlyReportSentRepository;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.repository.UserRepository;
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
 * Интеграционные тесты сервиса месячного отчёта (issue #137) на реальном Postgres
 * через Testcontainers — реальные агрегаты ({@code COUNT}, {@code GROUP BY},
 * {@code HAVING MIN(CASE...)}) прогоняются на Postgres-диалекте, а не на H2.
 *
 * <p>Покрывает AC:
 *   - happy path: ≥3 активных действия за прошлый месяц → отчёт с корректными метриками;
 *   - порог: ровно 2 → нет отчёта, ровно 3 → есть;
 *   - 0 действий и blocked-юзер → нет отчёта;
 *   - идемпотентность через {@link MonthlyReportSentRepository} и race на markSent;
 *   - не-UTC TZ: действие в последний день месяца 23:00 локально (в UTC уже след. месяц)
 *     учитывается в локальном месяце; year_month = YYYYMM прошлого месяца в TZ юзера.
 */
@DisplayName("MonthlyReportService — месячный отчёт (issue #137)")
class MonthlyReportServiceTest extends IntegrationTestBase {

    @Autowired private MonthlyReportService reportService;

    @Autowired private UserRepository userRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private PlantRepository plantRepository;
    @Autowired private CareHistoryRepository careHistoryRepository;
    @Autowired private MonthlyReportSentRepository sentRepository;

    @AfterEach
    void cleanup() {
        sentRepository.deleteAll();
        careHistoryRepository.deleteAll();
        plantRepository.deleteAll();
        locationRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("should_build_report_with_correct_metrics_when_user_active_last_month")
    void should_build_report_with_correct_metrics_when_user_active_last_month() {
        // arrange: юзер в Москве; отчёт считается за апрель 2026, тик 1 мая в окне.
        User user = savedUser(3001L, "Europe/Moscow");
        // Самое старое живое растение — заведено 2 года назад.
        Plant oldest = savedPlant(user, "Старый фикус", LocalDate.of(2024, 5, 1));
        Plant monstera = savedPlant(user, "Монстера", LocalDate.of(2025, 12, 1));

        // Апрель 2026 в TZ Москвы. Все действия в середине месяца — без краёв.
        // Фикус: 2 полива (оба on-time) → flawless-кандидат.
        saveCare(oldest, TaskType.WATERING, moscow(2026, 4, 10, 12), true);
        saveCare(oldest, TaskType.WATERING, moscow(2026, 4, 20, 12), true);
        // Монстера: 1 полив on-time + 1 опрыскивание НЕ on-time + 1 удобрение on-time
        //  → не flawless (есть пропуск), но всего 3 действия у неё.
        saveCare(monstera, TaskType.WATERING, moscow(2026, 4, 5, 12), true);
        saveCare(monstera, TaskType.MISTING, moscow(2026, 4, 12, 12), false);
        saveCare(monstera, TaskType.FERTILIZING, moscow(2026, 4, 25, 12), true);

        // 2026-05-01 09:30 Москва — 1-е число, утреннее окно; прошлый месяц = апрель 2026.
        Instant now = moscow(2026, 5, 1, 9).plusSeconds(30 * 60);

        // act
        List<MonthlyReportService.MonthlyReport> due = reportService.findDueReports(now);

        // assert
        assertThat(due).hasSize(1);
        MonthlyReportService.MonthlyReport report = due.get(0);
        assertThat(report.userId()).isEqualTo(user.getId());
        assertThat(report.telegramChatId()).isEqualTo(3001L);
        assertThat(report.yearMonth()).isEqualTo(202604);
        assertThat(report.totalActions()).isEqualTo(5);
        assertThat(report.breakdown())
                .containsEntry(TaskType.WATERING, 3L)
                .containsEntry(TaskType.MISTING, 1L)
                .containsEntry(TaskType.FERTILIZING, 1L);
        assertThat(report.breakdown()).doesNotContainKey(TaskType.SOIL_CHECK);
        // Самое старое живое растение — фикус, заведён 2024-05-01, отчёт строится в мае 2026.
        assertThat(report.oldestPlant()).isNotNull();
        assertThat(report.oldestPlant().name()).isEqualTo("Старый фикус");
        assertThat(report.oldestPlant().years()).isEqualTo(2);
        // flawless: фикус (все on-time, 2 действия) обгоняет монстеру (есть не-on-time).
        assertThat(report.flawlessPlantName()).isEqualTo("Старый фикус");

        // текст содержит ключевые числа
        String text = report.buildText();
        assertThat(text)
                .contains("апрель 2026")
                .contains("5 действий")
                .contains("Старый фикус")
                .contains("Ни разу не пропустил уход за Старый фикус");
    }

    @Test
    @DisplayName("should_not_send_report_when_not_first_day_of_month")
    void should_not_send_report_when_not_first_day_of_month() {
        // arrange: данных в апреле достаточно для отчёта, но тик 2 мая 09:30 Москва —
        // НЕ 1-е число. Окно теперь считается в сервисе → агрегаты не запускаются.
        User user = savedUser(3101L, "Europe/Moscow");
        Plant plant = savedPlant(user, "Кактус", LocalDate.of(2025, 1, 1));
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 3, 10), true);
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 13, 10), true);
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 23, 10), true);

        Instant now = moscow(2026, 5, 2, 9).plusSeconds(30 * 60);

        // act
        List<MonthlyReportService.MonthlyReport> due = reportService.findDueReports(now);

        // assert
        assertThat(due).isEmpty();
    }

    @Test
    @DisplayName("should_not_send_report_when_before_morning_window")
    void should_not_send_report_when_before_morning_window() {
        // arrange: 1 мая 08:30 Москва — до начала окна 09:00.
        User user = savedUser(3102L, "Europe/Moscow");
        Plant plant = savedPlant(user, "Кактус", LocalDate.of(2025, 1, 1));
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 3, 10), true);
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 13, 10), true);
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 23, 10), true);

        Instant now = moscow(2026, 5, 1, 8).plusSeconds(30 * 60);

        // act
        List<MonthlyReportService.MonthlyReport> due = reportService.findDueReports(now);

        // assert
        assertThat(due).isEmpty();
    }

    @Test
    @DisplayName("should_not_send_report_when_at_window_end_boundary")
    void should_not_send_report_when_at_window_end_boundary() {
        // arrange: 1 мая 10:00 Москва — конец окна exclusive [09:00, 10:00).
        User user = savedUser(3103L, "Europe/Moscow");
        Plant plant = savedPlant(user, "Кактус", LocalDate.of(2025, 1, 1));
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 3, 10), true);
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 13, 10), true);
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 23, 10), true);

        Instant now = moscow(2026, 5, 1, 10);

        // act
        List<MonthlyReportService.MonthlyReport> due = reportService.findDueReports(now);

        // assert
        assertThat(due).isEmpty();
    }

    @Test
    @DisplayName("should_send_report_when_first_day_morning_in_almaty_zone")
    void should_send_report_when_first_day_morning_in_almaty_zone() {
        // arrange: окно считается в TZ юзера. 1 мая 04:30 UTC = 09:30 Алматы (UTC+5) → окно.
        // Для Москвы тот же момент = 07:30 (вне окна) — отчёт уходит только из-за TZ юзера.
        User user = savedUser(3104L, "Asia/Almaty");
        Plant plant = savedPlant(user, "Алматинский кактус", LocalDate.of(2025, 1, 1));
        saveCare(plant, TaskType.WATERING, almaty(2026, 4, 3, 10), true);
        saveCare(plant, TaskType.WATERING, almaty(2026, 4, 13, 10), true);
        saveCare(plant, TaskType.WATERING, almaty(2026, 4, 23, 10), true);

        Instant now = Instant.parse("2026-05-01T04:30:00Z");

        // act
        List<MonthlyReportService.MonthlyReport> due = reportService.findDueReports(now);

        // assert
        assertThat(due).hasSize(1);
        assertThat(due.get(0).yearMonth()).isEqualTo(202604);
    }

    @Test
    @DisplayName("should_send_report_when_exactly_three_actions")
    void should_send_report_when_exactly_three_actions() {
        // arrange: ровно порог MIN_ACTIONS = 3
        User user = savedUser(3002L, "Europe/Moscow");
        Plant plant = savedPlant(user, "Кактус", LocalDate.of(2025, 1, 1));
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 3, 10), true);
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 13, 10), true);
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 23, 10), true);

        Instant now = moscow(2026, 5, 1, 9);

        // act
        List<MonthlyReportService.MonthlyReport> due = reportService.findDueReports(now);

        // assert
        assertThat(due).hasSize(1);
        assertThat(due.get(0).totalActions()).isEqualTo(3);
    }

    @Test
    @DisplayName("should_not_send_report_when_exactly_two_actions")
    void should_not_send_report_when_exactly_two_actions() {
        // arrange: на одно действие меньше порога
        User user = savedUser(3003L, "Europe/Moscow");
        Plant plant = savedPlant(user, "Кактус", LocalDate.of(2025, 1, 1));
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 3, 10), true);
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 13, 10), true);

        Instant now = moscow(2026, 5, 1, 9);

        // act
        List<MonthlyReportService.MonthlyReport> due = reportService.findDueReports(now);

        // assert
        assertThat(due).isEmpty();
    }

    @Test
    @DisplayName("should_not_send_report_when_no_activity_at_all")
    void should_not_send_report_when_no_activity_at_all() {
        // arrange: растение есть, но за прошлый месяц ноль действий
        User user = savedUser(3004L, "Europe/Moscow");
        savedPlant(user, "Молчун", LocalDate.of(2025, 1, 1));

        Instant now = moscow(2026, 5, 1, 9);

        // act
        List<MonthlyReportService.MonthlyReport> due = reportService.findDueReports(now);

        // assert
        assertThat(due).isEmpty();
    }

    @Test
    @DisplayName("should_ignore_cancelled_actions_when_counting")
    void should_ignore_cancelled_actions_when_counting() {
        // arrange: 4 записи, но одна отменена (cancelled) → активных 3.
        User user = savedUser(3005L, "Europe/Moscow");
        Plant plant = savedPlant(user, "Алоэ", LocalDate.of(2025, 1, 1));
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 3, 10), true);
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 13, 10), true);
        CareHistory cancelled = saveCare(plant, TaskType.WATERING, moscow(2026, 4, 20, 10), true);
        CareHistory compensator = saveCare(plant, TaskType.WATERING, moscow(2026, 4, 23, 10), true);
        cancelled.setCancelledBy(compensator);
        careHistoryRepository.save(cancelled);

        Instant now = moscow(2026, 5, 1, 9);

        // act
        List<MonthlyReportService.MonthlyReport> due = reportService.findDueReports(now);

        // assert: cancelled запись не считается → активных ровно 3 (порог пройден)
        assertThat(due).hasSize(1);
        assertThat(due.get(0).totalActions()).isEqualTo(3);
    }

    @Test
    @DisplayName("should_be_idempotent_when_report_already_sent_for_year_month")
    void should_be_idempotent_when_report_already_sent_for_year_month() {
        // arrange: отчёт за апрель уже отправлен (есть запись в monthly_report_sent)
        User user = savedUser(3006L, "Europe/Moscow");
        Plant plant = savedPlant(user, "Фиалка", LocalDate.of(2025, 1, 1));
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 3, 10), true);
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 13, 10), true);
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 23, 10), true);

        Instant now = moscow(2026, 5, 1, 9);

        // act 1 — кандидат есть, «отправляем» и помечаем
        List<MonthlyReportService.MonthlyReport> firstRun = reportService.findDueReports(now);
        assertThat(firstRun).hasSize(1);
        reportService.markSent(user.getId(), 202604, now);

        // act 2 — повторный запуск того же тика
        List<MonthlyReportService.MonthlyReport> secondRun = reportService.findDueReports(now);

        // assert
        assertThat(secondRun).isEmpty();
        assertThat(sentRepository.findById(new MonthlyReportSentId(user.getId(), 202604)))
                .isPresent();
        assertThat(sentRepository.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("should_not_create_second_row_when_mark_sent_called_twice")
    void should_not_create_second_row_when_mark_sent_called_twice() {
        // arrange
        User user = savedUser(3007L, "Europe/Moscow");

        // act: повторный markSent того же PK не должен падать (идемпотентность) и не
        // должен плодить вторую строку — INSERT ... ON CONFLICT DO NOTHING делает
        // второй вызов no-op на уровне БД, не перетирая sent_at первого.
        reportService.markSent(user.getId(), 202604, Instant.parse("2026-05-01T06:00:00Z"));
        reportService.markSent(user.getId(), 202604, Instant.parse("2026-05-01T07:00:00Z"));

        // assert: ровно одна запись на (user_id, year_month), sent_at — от первого вызова.
        assertThat(sentRepository.count()).isEqualTo(1L);
        assertThat(sentRepository.findById(new MonthlyReportSentId(user.getId(), 202604)))
                .isPresent()
                .get()
                .extracting(MonthlyReportSent::getSentAt)
                .isEqualTo(Instant.parse("2026-05-01T06:00:00Z"));
    }

    @Test
    @DisplayName("should_not_throw_when_mark_sent_for_already_persisted_pk")
    void should_not_throw_when_mark_sent_for_already_persisted_pk() {
        // arrange: запись уже есть в БД (как после успешного предыдущего тика).
        // markSent для того же PK обязан остаться идемпотентным — не пробросить
        // исключение наружу (INSERT ... ON CONFLICT DO NOTHING → no-op).
        User user = savedUser(3014L, "Europe/Moscow");
        sentRepository.saveAndFlush(
                new MonthlyReportSent(user.getId(), 202604, Instant.parse("2026-05-01T06:00:00Z")));

        // act + assert: не бросает, остаётся ровно одна строка
        reportService.markSent(user.getId(), 202604, Instant.parse("2026-05-01T07:00:00Z"));

        assertThat(sentRepository.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("should_skip_blocked_users")
    void should_skip_blocked_users() {
        // arrange
        User user = savedUser(3008L, "Europe/Moscow");
        user.setBlocked(true);
        userRepository.save(user);
        Plant plant = savedPlant(user, "Цветок", LocalDate.of(2025, 1, 1));
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 3, 10), true);
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 13, 10), true);
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 23, 10), true);

        Instant now = moscow(2026, 5, 1, 9);

        // act
        List<MonthlyReportService.MonthlyReport> due = reportService.findDueReports(now);

        // assert
        assertThat(due).isEmpty();
    }

    @Test
    @DisplayName("should_count_action_in_local_month_when_done_late_last_day_almaty")
    void should_count_action_in_local_month_when_done_late_last_day_almaty() {
        // arrange: юзер в Алматы (UTC+5, без перехода). Действие сделано в последний
        // день апреля в 23:00 локально. В UTC это 30 апреля 18:00 — всё ещё апрель UTC,
        // НО проверяем зеркальный кейс отдельно (см. moscow-тест ниже). Здесь главное —
        // что 1 мая 00:30 локально действие НЕ протекает в майский месяц.
        User user = savedUser(3009L, "Asia/Almaty");
        Plant plant = savedPlant(user, "Алматинское", LocalDate.of(2025, 1, 1));

        // Два действия в апреле, и одно — 1 мая 00:30 ЛОКАЛЬНО (Алматы), что в UTC
        // ещё 30 апреля 19:30. Это майское действие НЕ должно попасть в апрельский отчёт.
        saveCare(plant, TaskType.WATERING, almaty(2026, 4, 15, 10), true);
        saveCare(plant, TaskType.WATERING, almaty(2026, 4, 20, 10), true);
        saveCare(plant, TaskType.WATERING, almaty(2026, 5, 1, 0).plusSeconds(30 * 60), true);

        // тик 2026-05-01 09:00 локально Алматы (1-е число, окно) → прошлый месяц = апрель 2026
        Instant now = almaty(2026, 5, 1, 9);

        // act
        List<MonthlyReportService.MonthlyReport> due = reportService.findDueReports(now);

        // assert: только 2 апрельских действия → меньше порога, отчёта нет.
        // Если бы границы месяца считались в UTC, майское действие (30 апр UTC) ошибочно
        // попало бы в апрель и дало бы 3 → отчёт. Значит отсутствие отчёта доказывает
        // расчёт границ в TZ юзера.
        assertThat(due).isEmpty();
    }

    @Test
    @DisplayName("should_include_action_done_late_last_day_in_local_month_almaty")
    void should_include_action_done_late_last_day_in_local_month_almaty() {
        // arrange: действие 30 апреля 23:00 ЛОКАЛЬНО Алматы = 30 апреля 18:00 UTC.
        // Это апрель и в UTC, и локально — но добавим контрольный кейс: действие
        // 1 апреля 02:00 локально (= 31 марта 21:00 UTC). По UTC-календарю это март,
        // по локальному — апрель. Оно ДОЛЖНО учитываться в апреле.
        User user = savedUser(3010L, "Asia/Almaty");
        Plant plant = savedPlant(user, "Граничное", LocalDate.of(2025, 1, 1));

        // 1 апреля 02:00 локально = 31 марта 21:00 UTC — по локальному календарю апрель.
        saveCare(plant, TaskType.WATERING, almaty(2026, 4, 1, 2), true);
        saveCare(plant, TaskType.WATERING, almaty(2026, 4, 15, 10), true);
        saveCare(plant, TaskType.WATERING, almaty(2026, 4, 20, 10), true);

        // тик 2026-05-01 09:00 локально Алматы (1-е число, окно).
        Instant now = almaty(2026, 5, 1, 9);

        // act
        List<MonthlyReportService.MonthlyReport> due = reportService.findDueReports(now);

        // assert: все 3 в апреле локально → отчёт есть, year_month = 202604.
        // Если бы границы считались в UTC, действие 31 марта 21:00 UTC выпало бы из
        // апреля → 2 действия → нет отчёта.
        assertThat(due).hasSize(1);
        assertThat(due.get(0).yearMonth()).isEqualTo(202604);
        assertThat(due.get(0).totalActions()).isEqualTo(3);
    }

    @Test
    @DisplayName("should_use_previous_local_month_year_month_in_western_zone")
    void should_use_previous_local_month_year_month_in_western_zone() {
        // arrange: западная TZ (UTC-7..-8). Тик 1 мая 09:00 локально LA = 1 мая 16:00 UTC.
        // Прошлый месяц в TZ юзера = апрель → year_month 202604.
        User user = savedUser(3011L, "America/Los_Angeles");
        Plant plant = savedPlant(user, "Калифорнийское", LocalDate.of(2025, 1, 1));
        saveCare(plant, TaskType.WATERING, la(2026, 4, 10, 12), true);
        saveCare(plant, TaskType.WATERING, la(2026, 4, 15, 12), true);
        saveCare(plant, TaskType.WATERING, la(2026, 4, 20, 12), true);

        Instant now = la(2026, 5, 1, 9);

        // act
        List<MonthlyReportService.MonthlyReport> due = reportService.findDueReports(now);

        // assert
        assertThat(due).hasSize(1);
        assertThat(due.get(0).yearMonth()).isEqualTo(202604);
        assertThat(due.get(0).totalActions()).isEqualTo(3);
    }

    @Test
    @DisplayName("should_exclude_archived_plant_from_flawless_and_oldest")
    void should_exclude_archived_plant_from_flawless_and_oldest() {
        // arrange: самое старое растение архивировано → не должно попасть ни в
        // oldest, ни в flawless; берётся следующее живое.
        User user = savedUser(3012L, "Europe/Moscow");
        Plant archived = savedPlant(user, "Архивный старик", LocalDate.of(2020, 1, 1));
        archived.setArchivedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        plantRepository.save(archived);
        Plant living = savedPlant(user, "Живой", LocalDate.of(2024, 1, 1));

        // Действия только у живого, все on-time.
        saveCare(living, TaskType.WATERING, moscow(2026, 4, 3, 10), true);
        saveCare(living, TaskType.WATERING, moscow(2026, 4, 13, 10), true);
        saveCare(living, TaskType.WATERING, moscow(2026, 4, 23, 10), true);

        Instant now = moscow(2026, 5, 1, 9);

        // act
        List<MonthlyReportService.MonthlyReport> due = reportService.findDueReports(now);

        // assert
        assertThat(due).hasSize(1);
        MonthlyReportService.MonthlyReport report = due.get(0);
        assertThat(report.oldestPlant().name()).isEqualTo("Живой");
        assertThat(report.flawlessPlantName()).isEqualTo("Живой");
    }

    @Test
    @DisplayName("should_have_no_flawless_plant_when_every_plant_missed_at_least_once")
    void should_have_no_flawless_plant_when_every_plant_missed_at_least_once() {
        // arrange: у единственного растения есть хотя бы одно не-on-time действие
        User user = savedUser(3013L, "Europe/Moscow");
        Plant plant = savedPlant(user, "Прогульщик", LocalDate.of(2024, 1, 1));
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 3, 10), true);
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 13, 10), false);
        saveCare(plant, TaskType.WATERING, moscow(2026, 4, 23, 10), true);

        Instant now = moscow(2026, 5, 1, 9);

        // act
        List<MonthlyReportService.MonthlyReport> due = reportService.findDueReports(now);

        // assert: отчёт есть (3 действия), но flawless нет (HAVING MIN(...) отсёк)
        assertThat(due).hasSize(1);
        assertThat(due.get(0).flawlessPlantName()).isNull();
        assertThat(due.get(0).buildText()).doesNotContain("Ни разу не пропустил");
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

    /**
     * Сохраняет запись ухода, у которой {@code done_at} — это локальный момент
     * {@code localInstant} (TZ юзера), сконвертированный в UTC LocalDateTime —
     * ровно так, как done_at хранится в БД (UTC).
     */
    private CareHistory saveCare(Plant plant, TaskType type, Instant localInstant, boolean onTime) {
        LocalDateTime doneAtUtc = localInstant.atZone(ZoneOffset.UTC)
                .toLocalDateTime().truncatedTo(ChronoUnit.MICROS);
        return careHistoryRepository.save(CareHistory.builder()
                .plant(plant)
                .taskType(type)
                .doneAt(doneAtUtc)
                .onTime(onTime)
                .build());
    }

    private static Instant moscow(int y, int mo, int d, int h) {
        return LocalDateTime.of(y, mo, d, h, 0).atZone(ZoneId.of("Europe/Moscow")).toInstant();
    }

    private static Instant almaty(int y, int mo, int d, int h) {
        return LocalDateTime.of(y, mo, d, h, 0).atZone(ZoneId.of("Asia/Almaty")).toInstant();
    }

    private static Instant la(int y, int mo, int d, int h) {
        return LocalDateTime.of(y, mo, d, h, 0).atZone(ZoneId.of("America/Los_Angeles")).toInstant();
    }
}

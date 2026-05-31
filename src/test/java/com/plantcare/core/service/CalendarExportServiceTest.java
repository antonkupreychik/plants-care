package com.plantcare.core.service;

import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.LocationRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.bot.support.IntegrationTestBase;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Интеграционные тесты CalendarExportService — экспорт расписаний в .ics")
@TestPropertySource(properties = {
        "app.calendar.base-url=https://test.local",
        "app.calendar.event-window-days=90",
        // FixedClockConfig переопределяет продакшен-бин Clock из PlantsCareApplication
        "spring.main.allow-bean-definition-overriding=true"
})
@Import(CalendarExportServiceTest.FixedClockConfig.class)
class CalendarExportServiceTest extends IntegrationTestBase {

    /**
     * Фиксированный момент времени: 2026-05-23T10:00:00Z. Все события считаются от него.
     */
    static final Instant FIXED_NOW = Instant.parse("2026-05-23T10:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @Autowired private CalendarExportService calendarExportService;
    @Autowired private UserRepository userRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private PlantRepository plantRepository;
    @Autowired private CareScheduleRepository careScheduleRepository;

    @AfterEach
    void cleanup() {
        careScheduleRepository.deleteAll();
        plantRepository.deleteAll();
        locationRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ==================== getOrCreateToken ====================

    @Test
    @DisplayName("getOrCreateToken: генерирует токен у пользователя без него и сохраняет в БД")
    void should_generate_new_token_when_user_has_none() {
        User user = userRepository.save(User.builder()
                .telegramChatId(500L)
                .build());

        String token = calendarExportService.getOrCreateToken(user);

        assertThat(token)
                .isNotNull()
                .hasSize(43)            // 32 байта в URL-safe base64 без паддинга = 43 символа
                .matches("[A-Za-z0-9_-]+");

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getCalendarToken()).isEqualTo(token);
    }

    @Test
    @DisplayName("getOrCreateToken: возвращает существующий токен, не меняя значение")
    void should_return_existing_token_when_user_already_has_one() {
        User user = userRepository.save(User.builder()
                .telegramChatId(501L)
                .calendarToken("preexisting-token-value")
                .build());

        String token = calendarExportService.getOrCreateToken(user);

        assertThat(token).isEqualTo("preexisting-token-value");

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getCalendarToken()).isEqualTo("preexisting-token-value");
    }

    // ==================== buildIcsForToken ====================

    @Test
    @DisplayName("buildIcsForToken: незнакомый токен → EntityNotFoundException")
    void should_throw_when_token_not_found() {
        assertThatThrownBy(() -> calendarExportService.buildIcsForToken("nonexistent"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Calendar token not found");
    }

    // ==================== buildCalendarUrl ====================

    @Test
    @DisplayName("buildCalendarUrl: URL собирается из base-url, токена и .ics")
    void should_build_calendar_url() {
        String url = calendarExportService.buildCalendarUrl("abc");

        assertThat(url).isEqualTo("https://test.local/calendar/abc.ics");
    }

    // ==================== Рендер ICS — обёртка VCALENDAR ====================

    @Test
    @DisplayName("ICS содержит обязательную обёртку VCALENDAR (BEGIN/VERSION/PRODID/END)")
    void should_render_ics_with_vcalendar_envelope() {
        User user = saveUserWithToken("token-vcal");
        Plant plant = savePlant(user, "Монстера");
        saveSchedule(plant, TaskType.WATERING,
                LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC).plusDays(7));

        String ics = calendarExportService.buildIcsForToken("token-vcal");

        assertThat(ics)
                .contains("BEGIN:VCALENDAR")
                .contains("VERSION:2.0")
                .contains("PRODID:-//PlantsCare//Bot Calendar//EN")
                .contains("END:VCALENDAR");
    }

    @Test
    @DisplayName("ICS пуст (нет VEVENT), когда у пользователя нет расписаний")
    void should_render_empty_calendar_when_no_schedules() {
        saveUserWithToken("token-empty");

        String ics = calendarExportService.buildIcsForToken("token-empty");

        assertThat(ics)
                .contains("BEGIN:VCALENDAR")
                .contains("END:VCALENDAR")
                .doesNotContain("BEGIN:VEVENT");
    }

    // ==================== Рендер ICS — All-Day события ====================

    @Test
    @DisplayName("VEVENT — All-Day: DTSTART;VALUE=DATE: с датой формата YYYYMMDD, без времени")
    void should_render_vevent_as_all_day() {
        User user = saveUserWithToken("token-allday");
        Plant plant = savePlant(user, "Фикус");
        // FIXED_NOW = 2026-05-23 → +7 дней = 2026-05-30, DTEND = следующий день 2026-05-31
        saveSchedule(plant, TaskType.WATERING,
                LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC).plusDays(7));

        String ics = calendarExportService.buildIcsForToken("token-allday");

        assertThat(ics)
                .contains("BEGIN:VEVENT")
                .contains("DTSTART;VALUE=DATE:20260530")
                .contains("DTEND;VALUE=DATE:20260531")
                .doesNotContain("DTSTART;VALUE=DATE-TIME")
                .doesNotContain("DTSTART:2026");      // полная DATE-TIME форма
    }

    // ==================== Окно событий ====================

    @Test
    @DisplayName("Только события в окне [now; now+90d) попадают в ICS")
    void should_only_include_schedules_within_window() {
        User user = saveUserWithToken("token-window");
        Plant plant = savePlant(user, "Кактус");

        LocalDateTime now = LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);
        CareSchedule inWindow = saveSchedule(plant, TaskType.WATERING, now.plusDays(30));
        saveSchedule(plant, TaskType.MISTING, now.plusDays(120));

        String ics = calendarExportService.buildIcsForToken("token-window");

        assertThat(ics)
                .contains("UID:plants-care-schedule-" + inWindow.getId() + "@plants-care")
                .contains("DTSTART;VALUE=DATE:20260622");      // now + 30 = 2026-06-22

        // Внутри окна — ровно одно VEVENT
        assertThat(countOccurrences(ics, "BEGIN:VEVENT")).isEqualTo(1);
    }

    @Test
    @DisplayName("Расписания архивных растений не рендерятся как события")
    void should_exclude_archived_plants() {
        User user = saveUserWithToken("token-archived");
        Plant plant = savePlant(user, "Зомби-фикус");
        plant.setArchivedAt(LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC).minusDays(1));
        plantRepository.save(plant);

        saveSchedule(plant, TaskType.WATERING,
                LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC).plusDays(5));

        String ics = calendarExportService.buildIcsForToken("token-archived");

        assertThat(ics)
                .contains("BEGIN:VCALENDAR")
                .doesNotContain("BEGIN:VEVENT");
    }

    @Test
    @DisplayName("Неактивные расписания не рендерятся как события")
    void should_exclude_inactive_schedules() {
        User user = saveUserWithToken("token-inactive");
        Plant plant = savePlant(user, "Пальма");

        CareSchedule schedule = saveSchedule(plant, TaskType.WATERING,
                LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC).plusDays(3));
        schedule.setActive(false);
        careScheduleRepository.save(schedule);

        String ics = calendarExportService.buildIcsForToken("token-inactive");

        assertThat(ics).doesNotContain("BEGIN:VEVENT");
    }

    @Test
    @DisplayName("ICS чужого пользователя не содержит ничьих чужих расписаний")
    void should_isolate_calendars_between_users() {
        User alice = saveUserWithToken("token-alice");
        Plant alicePlant = savePlant(alice, "Алисина монстера");
        saveSchedule(alicePlant, TaskType.WATERING,
                LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC).plusDays(5));

        User bob = userRepository.save(User.builder()
                .telegramChatId(602L)
                .calendarToken("token-bob")
                .build());
        Plant bobPlant = savePlant(bob, "Бобов кактус");
        saveSchedule(bobPlant, TaskType.WATERING,
                LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC).plusDays(5));

        String aliceIcs = calendarExportService.buildIcsForToken("token-alice");

        assertThat(aliceIcs)
                .contains("Алисина монстера")
                .doesNotContain("Бобов кактус");
    }

    // ==================== ICS escaping (RFC 5545) ====================

    @Test
    @DisplayName("Спецсимволы (; , \\n \\) в имени растения экранируются в SUMMARY")
    void should_escape_ics_special_chars_in_summary() {
        User user = saveUserWithToken("token-escape");
        // Имя растения, содержащее все четыре спецсимвола, требующих escape по RFC 5545
        Plant plant = savePlant(user, "A;B,C\nD\\E");
        saveSchedule(plant, TaskType.WATERING,
                LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC).plusDays(4));

        String ics = calendarExportService.buildIcsForToken("token-escape");

        // Сначала экранируется бэкслеш, потом остальное → в SUMMARY ожидаем:
        //   ; → \;
        //   , → \,
        //   \n → \n (literal backslash + n)
        //   \ → \\
        assertThat(ics).contains("SUMMARY:💧 Полив A\\;B\\,C\\nD\\\\E");

        // Голых неэкранированных спецсимволов в SUMMARY быть не должно:
        // каждый ';' и ',' обязан быть префиксован одиночным '\' (negative lookbehind).
        String summaryLine = ics.lines()
                .filter(l -> l.startsWith("SUMMARY:"))
                .findFirst()
                .orElseThrow();
        assertThat(summaryLine)
                .as("неэкранированные ; в SUMMARY")
                .doesNotMatch(".*(?<!\\\\);.*");
        assertThat(summaryLine)
                .as("неэкранированные , в SUMMARY")
                .doesNotMatch(".*(?<!\\\\),.*");
    }

    // ==================== Helpers ====================

    private static final java.util.concurrent.atomic.AtomicLong CHAT_ID_SEQ =
            new java.util.concurrent.atomic.AtomicLong(900_000L);

    private User saveUserWithToken(String token) {
        return userRepository.save(User.builder()
                .telegramChatId(CHAT_ID_SEQ.incrementAndGet())
                .calendarToken(token)
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

    private CareSchedule saveSchedule(Plant plant, TaskType taskType, LocalDateTime nextDueAt) {
        return careScheduleRepository.save(CareSchedule.builder()
                .plant(plant)
                .taskType(taskType)
                .intervalDays(7)
                .nextDueAt(nextDueAt.truncatedTo(ChronoUnit.MICROS))
                .active(true)
                .build());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}

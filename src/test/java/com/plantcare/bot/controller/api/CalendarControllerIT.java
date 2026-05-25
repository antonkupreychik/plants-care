package com.plantcare.bot.controller.api;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Полный end-to-end smoke-тест публичного маршрута {@code GET /calendar/{token}.ics}:
 * реальный embedded web-server, реальный PostgreSQL (Testcontainers), реальный Spring Security
 * default chain ({@code permitAll}).
 *
 * <p>Используем собственный контейнер (а не {@link com.plantcare.bot.support.IntegrationTestBase})
 * чтобы получить {@code RANDOM_PORT} веб-окружение — IntegrationTestBase его не объявляет.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@DisplayName("E2E smoke: GET /calendar/{token}.ics")
class CalendarControllerIT {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("plants_care_test")
                    .withUsername("test")
                    .withPassword("test")
                    .withReuse(true);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private TestRestTemplate restTemplate;
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

    @Test
    @DisplayName("Happy path: создаём юзера+растение+расписание → GET выдаёт 200 text/calendar с VEVENT")
    void should_serve_real_ics_end_to_end() {
        User user = userRepository.save(User.builder()
                .telegramChatId(700L)
                .calendarToken("e2e-token-happy")
                .build());

        Location location = locationRepository.save(Location.builder()
                .user(user)
                .name(Location.DEFAULT_NAME)
                .emoji(Location.DEFAULT_EMOJI)
                .defaultLocation(true)
                .build());

        Plant plant = plantRepository.save(Plant.builder()
                .user(user)
                .location(location)
                .name("Монстера E2E")
                .build());

        careScheduleRepository.save(CareSchedule.builder()
                .plant(plant)
                .taskType(TaskType.WATERING)
                .intervalDays(7)
                .nextDueAt(LocalDateTime.now().plusDays(7).truncatedTo(ChronoUnit.MICROS))
                .active(true)
                .build());

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/calendar/e2e-token-happy.ics", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .as("Content-Type должен быть text/calendar")
                .isNotNull();
        assertThat(response.getHeaders().getContentType().toString())
                .startsWith("text/calendar");

        String body = response.getBody();
        assertThat(body)
                .isNotNull()
                .contains("BEGIN:VCALENDAR")
                .contains("END:VCALENDAR")
                .contains("BEGIN:VEVENT")
                .contains("Монстера E2E");
    }
}

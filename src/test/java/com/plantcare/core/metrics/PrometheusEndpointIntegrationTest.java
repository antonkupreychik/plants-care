package com.plantcare.core.metrics;

import com.plantcare.core.domain.enums.TaskType;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke-тест публичной доступности /actuator/prometheus в test-профиле.
 *
 * <p>В тестовом конфиге admin-креды не заданы → {@code prometheusSecurityFilterChain}
 * c {@link org.springframework.boot.autoconfigure.condition.ConditionalOnExpression}
 * не регистрируется, и эндпоинт падает в default chain (permitAll). Это
 * ожидаемое поведение — реальная защита через basic-auth проверяется только
 * на прод/staging, где есть {@code ADMIN_USERNAME}.
 *
 * <p>Что проверяем:
 * <ul>
 *   <li>endpoint реально включён в {@code management.endpoints.web.exposure.include};</li>
 *   <li>после события «зарегистрировали юзера» в теле появляется
 *       {@code users_registered_total} — значит Prometheus-формат собирается
 *       и наши кастомные имена .-нотации корректно конвертируются в _.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@DisplayName("Smoke: GET /actuator/prometheus")
class PrometheusEndpointIntegrationTest {

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
    @Autowired private MetricsService metricsService;

    @Test
    @DisplayName("Endpoint отдаёт 200 OK в test-профиле (без admin-кредов)")
    void should_return_200_when_admin_disabled_in_test_profile() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotBlank();
    }

    @Test
    @DisplayName("Тело содержит наши кастомные метрики в Prometheus-формате (точки → подчёркивания)")
    void should_expose_custom_business_metrics_in_prometheus_format() {
        // Зажигаем по разу каждый из основных counter'ов, чтобы они появились в registry.
        // Без этого Micrometer регистрирует counter лениво — на первом инкременте.
        metricsService.recordUserRegistered();
        metricsService.recordNotificationSent(MetricsService.CHANNEL_TELEGRAM, TaskType.WATERING);
        metricsService.recordDigestSent();
        metricsService.updateActiveDau(5L);

        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = response.getBody();
        assertThat(body)
                .as("Prometheus body should expose our custom counters with dots converted to underscores")
                .contains("users_registered_total")
                .contains("notifications_sent_total")
                .contains("notifications_digest_sent_total")
                .contains("users_active_dau");
    }

    @Test
    @DisplayName("Тэги notifications_sent попадают в Prometheus-формат (channel, task_type)")
    void should_include_low_cardinality_tags_in_prometheus_output() {
        metricsService.recordNotificationSent(MetricsService.CHANNEL_TELEGRAM, TaskType.MISTING);

        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Не привязываемся к точному порядку label'ов — проверяем сам факт их наличия.
        assertThat(response.getBody())
                .contains("channel=\"telegram\"")
                .contains("task_type=\"MISTING\"");
    }
}

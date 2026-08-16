package com.plantcare.core.metrics;

import com.plantcare.core.domain.enums.TaskType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke-тест публичной доступности /actuator/prometheus в test-профиле
 * без admin-кредов.
 *
 * <p>Когда admin.username/password-bcrypt-hash пусты → {@code prometheusSecurityFilterChain}
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
        // Explicitly disable admin panel so prometheusSecurityFilterChain is NOT registered
        // and /actuator/prometheus remains public (falls through to the default permitAll chain).
        // This overrides the admin.* defaults set in application-test.yml, which exist to
        // stabilise context-cache keys for the full-context admin integration tests.
        registry.add("admin.username", () -> "");
        registry.add("admin.password-bcrypt-hash", () -> "");
    }

    // Boot 4 удалил TestRestTemplate; замена — RestTestClient из spring-test,
    // автоконфигурации для него нет, поэтому строим его на @LocalServerPort.
    @LocalServerPort private int port;
    @Autowired private MetricsService metricsService;

    private RestTestClient client() {
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    @DisplayName("Endpoint отдаёт 200 OK когда admin отключён (пустые креды)")
    void should_return_200_when_admin_disabled_in_test_profile() {
        String body = client().get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult().getResponseBody();

        assertThat(body).isNotBlank();
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

        String body = client().get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult().getResponseBody();

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

        String body = client().get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult().getResponseBody();

        // Не привязываемся к точному порядку label'ов — проверяем сам факт их наличия.
        assertThat(body)
                .contains("channel=\"telegram\"")
                .contains("task_type=\"MISTING\"");
    }
}

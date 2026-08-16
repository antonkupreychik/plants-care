package com.plantcare.bot.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Базовый класс для интеграционных тестов.
 *
 * Поднимает один экземпляр PostgreSQL и один Redis на всю тестовую сессию
 * (через статические контейнеры с .withReuse(true)), что в разы быстрее, чем
 * стартовать новые контейнеры для каждого тестового класса.
 *
 * Для повторного использования контейнеров между запусками нужно создать файл
 * ~/.testcontainers.properties со строкой:
 *   testcontainers.reuse.enable=true
 * Без этого .withReuse игнорируется (это сознательный opt-in от Testcontainers).
 *
 * Issue #278, эпик #277 фаза 0: Redis Testcontainers — база для фаз 2–4.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(NoOpShedLockTestConfig.class)
public abstract class IntegrationTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("plants_care_test")
                    .withUsername("test")
                    .withPassword("test")
                    .withReuse(true);

    /**
     * Redis 7 через GenericContainer (Testcontainers не имеет отдельного RedisContainer
     * в официальном модуле — используем GenericContainer с образом redis:7-alpine).
     * Порт 6379 проксируется на случайный хостовый, конфигурируется через DynamicPropertySource.
     */
    @SuppressWarnings("resource")
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withReuse(true);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void configureInfrastructure(DynamicPropertyRegistry registry) {
        // PostgreSQL
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // Redis: подключаем через host/port (не url), чтобы не конфликтовать
        // с REDIS_URL env-переменной Railway (она пустая в тестах).
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
        // url НЕ задаём: пустая строка ("") ломает парсер Lettuce
        // (RedisUrlSyntaxException: Invalid Redis URL '') и роняет контекст.
        // В базовом профиле url отсутствует, поэтому host/port (выше) берут приоритет сами.
    }
}

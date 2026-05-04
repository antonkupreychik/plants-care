package com.plantcare.bot.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Базовый класс для интеграционных тестов.
 *
 * Поднимает один экземпляр PostgreSQL на всю тестовую сессию (через статический
 * контейнер с .withReuse(true)), что в разы быстрее, чем стартовать новый
 * контейнер для каждого тестового класса.
 *
 * Для повторного использования контейнера между запусками нужно создать файл
 * ~/.testcontainers.properties со строкой:
 *   testcontainers.reuse.enable=true
 * Без этого .withReuse игнорируется (это сознательный opt-in от Testcontainers).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public abstract class IntegrationTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES =
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
}

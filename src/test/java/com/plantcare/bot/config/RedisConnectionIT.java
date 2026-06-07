package com.plantcare.bot.config;

import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.data.redis.RedisHealthIndicator;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #278, эпик #277 фаза 0: интеграционный тест Redis-инфраструктуры.
 *
 * <p>Проверяет:
 * <ul>
 *   <li>RedisConnectionFactory доступна и создаётся корректно.</li>
 *   <li>RedisTemplate инжектируется и выполняет базовые команды (ping).</li>
 *   <li>RedisHealthIndicator возвращает UP при доступном Redis.</li>
 *   <li>Namespacing ключей (префикс {@code pc:}) через RedisProperties.</li>
 * </ul>
 *
 * <p>Redis в тестах поднимается через Testcontainers (GenericContainer redis:7-alpine),
 * конфигурируется в {@link IntegrationTestBase}.
 */
class RedisConnectionIT extends IntegrationTestBase {

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RedisProperties redisProperties;

    @Test
    void should_connect_to_redis_when_container_is_running() {
        // act
        var connection = redisConnectionFactory.getConnection();

        // assert
        assertThat(connection).isNotNull();
        assertThat(connection.ping()).isEqualTo("PONG");

        connection.close();
    }

    @Test
    void should_write_and_read_value_when_redis_is_available() {
        var key = redisProperties.keyPrefix() + "test:redis-connection-it";
        var value = "hello-redis-278";

        // act
        redisTemplate.opsForValue().set(key, value);
        var result = redisTemplate.opsForValue().get(key);

        // assert
        assertThat(result).isEqualTo(value);

        // cleanup
        redisTemplate.delete(key);
    }

    @Test
    void should_have_pc_key_prefix_by_default() {
        // assert
        assertThat(redisProperties.keyPrefix()).isEqualTo("pc:");
    }

    @Test
    void should_return_up_status_in_health_indicator_when_redis_is_reachable() {
        // arrange
        var healthIndicator = new RedisHealthIndicator(redisConnectionFactory);

        // act
        Health health = healthIndicator.health();

        // assert
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKey("version");
    }
}

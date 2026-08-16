package com.plantcare.core.ratelimit;

import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.config.RedisProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Интеграционный тест распределённого rate-limit на Redis (issue #280, эпик #277 фаза 2).
 *
 * <p>AC:
 * <ul>
 *   <li>Счётчик общий на все инстансы — два разных {@link RedisRateLimiter} над одним
 *       Redis видят один и тот же счётчик (симуляция multi-instance: два контекста,
 *       общий store).</li>
 *   <li>Падение Redis не роняет API — fail-open: при недоступном Redis трафик
 *       пропускается, исключения не пробрасываются, счёт деградирует на Caffeine L1.</li>
 *   <li>Атомарный INCR+EXPIRE: ключ получает TTL, по истечении окна счётчик сбрасывается.</li>
 * </ul>
 */
class RedisRateLimiterIT extends IntegrationTestBase {

    private static final String SCOPE = "test-scope";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisProperties redisProperties;

    @AfterEach
    void cleanup() {
        var keys = stringRedisTemplate.keys(redisProperties.keyPrefix() + "rl:*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    private RedisRateLimiter newLimiter(boolean redisEnabled) {
        return new RedisRateLimiter(
                stringRedisTemplate,
                redisProperties,
                new RateLimitProperties(redisEnabled, 100_000L),
                new SimpleMeterRegistry());
    }

    // ═══════════════ AC: общий счётчик на все инстансы ═══════════════

    @Test
    void should_share_counter_across_two_instances_via_redis() {
        // arrange — два независимых лимитера над одним Redis (как два инстанса приложения)
        RedisRateLimiter instanceA = newLimiter(true);
        RedisRateLimiter instanceB = newLimiter(true);
        String key = "ip-" + UUID.randomUUID();
        Duration window = Duration.ofSeconds(60);
        int limit = 5;

        // act — 3 запроса на инстансе A, 2 на инстансе B → суммарно 5
        for (int i = 0; i < 3; i++) {
            instanceA.increment(SCOPE, key, window);
        }
        for (int i = 0; i < 2; i++) {
            instanceB.increment(SCOPE, key, window);
        }

        // assert — оба видят общий счётчик: лимит исчерпан с любого инстанса
        assertThat(instanceA.isOverLimit(SCOPE, key, limit, window)).isTrue();
        assertThat(instanceB.isOverLimit(SCOPE, key, limit, window)).isTrue();
    }

    @Test
    void should_not_block_below_limit_with_shared_counter() {
        // arrange
        RedisRateLimiter instanceA = newLimiter(true);
        RedisRateLimiter instanceB = newLimiter(true);
        String key = "ip-" + UUID.randomUUID();
        Duration window = Duration.ofSeconds(60);
        int limit = 5;

        // act — суммарно 4 запроса (под лимитом 5)
        instanceA.increment(SCOPE, key, window);
        instanceA.increment(SCOPE, key, window);
        instanceB.increment(SCOPE, key, window);
        instanceB.increment(SCOPE, key, window);

        // assert
        assertThat(instanceA.isOverLimit(SCOPE, key, limit, window)).isFalse();
        assertThat(instanceB.isOverLimit(SCOPE, key, limit, window)).isFalse();
    }

    @Test
    void should_set_ttl_on_window_key() {
        // arrange
        RedisRateLimiter limiter = newLimiter(true);
        String key = "ip-" + UUID.randomUUID();
        Duration window = Duration.ofSeconds(30);

        // act
        limiter.increment(SCOPE, key, window);

        // assert — у ключа выставлен TTL в пределах окна (атомарный INCR+EXPIRE)
        Long ttl = stringRedisTemplate.getExpire(
                redisProperties.keyPrefix() + "rl:" + SCOPE + ":" + key);
        assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(30L);
    }

    // ═══════════════ AC: fail-open при недоступном Redis ═══════════════

    @Test
    void should_fail_open_when_redis_is_down() {
        // arrange — Redis-шаблон, который бросает на любой команде (Redis недоступен)
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.opsForValue()).thenThrow(new RuntimeException("Redis down"));
        when(broken.execute(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.<Object>any()))
                .thenThrow(new RuntimeException("Redis down"));

        RedisRateLimiter limiter = new RedisRateLimiter(
                broken, redisProperties,
                new RateLimitProperties(true, 100_000L),
                new SimpleMeterRegistry());

        String key = "ip-down-" + UUID.randomUUID();
        Duration window = Duration.ofSeconds(60);
        int limit = 3;

        // act + assert — никаких исключений наружу (fail-open), трафик не блокируется
        assertThatNoException().isThrownBy(() -> {
            for (int i = 0; i < 10; i++) {
                limiter.increment(SCOPE, key, window);
            }
        });
        // деградация на L1: после превышения лимита локальный счётчик всё-таки блокирует,
        // но главное — Redis-сбой не уронил поток и не бросил исключение наружу
        assertThatNoException().isThrownBy(() -> limiter.isOverLimit(SCOPE, key, limit, window));
    }

    @Test
    void should_degrade_to_l1_counting_when_redis_is_down() {
        // arrange — Redis недоступен → лимитер обязан считать локально (L1)
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.opsForValue()).thenThrow(new RuntimeException("Redis down"));
        when(broken.execute(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.<Object>any()))
                .thenThrow(new RuntimeException("Redis down"));

        RedisRateLimiter limiter = new RedisRateLimiter(
                broken, redisProperties,
                new RateLimitProperties(true, 100_000L),
                new SimpleMeterRegistry());

        String key = "ip-l1-" + UUID.randomUUID();
        Duration window = Duration.ofSeconds(60);
        int limit = 3;

        // act — превышаем лимит локально
        for (int i = 0; i < 4; i++) {
            limiter.increment(SCOPE, key, window);
        }

        // assert — L1 ловит превышение (per-instance деградация), сбоя нет
        assertThat(limiter.isOverLimit(SCOPE, key, limit, window)).isTrue();
    }
}

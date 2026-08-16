package com.plantcare.core.ratelimit;

import com.plantcare.core.config.RedisProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для RedisRateLimiter")
class RedisRateLimiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private SimpleMeterRegistry registry;
    private RedisProperties redisProperties;

    private static final String SCOPE = "test";
    private static final String KEY = "user-1";
    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final int LIMIT = 5;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        redisProperties = new RedisProperties(null, null, "pc:");
    }

    private RedisRateLimiter rateLimiter(boolean redisEnabled) {
        return new RedisRateLimiter(
                redisTemplate,
                redisProperties,
                new RateLimitProperties(redisEnabled, 100_000L),
                registry);
    }

    // ═══════════════ isOverLimit ═══════════════

    @Nested
    @DisplayName("isOverLimit")
    class IsOverLimitTests {

        @Test
        @DisplayName("should_go_straight_to_L1_and_not_call_redis_when_redis_disabled")
        void should_go_straight_to_L1_and_not_call_redis_when_redis_disabled() {
            RedisRateLimiter limiter = rateLimiter(false);

            // L1 is empty, so counter = 0 → not over limit
            boolean result = limiter.isOverLimit(SCOPE, KEY, LIMIT, WINDOW);

            assertThat(result).isFalse();
            verify(redisTemplate, never()).opsForValue();
        }

        @Test
        @DisplayName("should_return_false_when_redis_enabled_and_counter_below_limit")
        void should_return_false_when_redis_enabled_and_counter_below_limit() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn("3");
            RedisRateLimiter limiter = rateLimiter(true);

            boolean result = limiter.isOverLimit(SCOPE, KEY, LIMIT, WINDOW);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should_return_true_and_increment_blocked_counter_when_redis_counter_at_limit")
        void should_return_true_and_increment_blocked_counter_when_redis_counter_at_limit() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(String.valueOf(LIMIT));
            RedisRateLimiter limiter = rateLimiter(true);

            boolean result = limiter.isOverLimit(SCOPE, KEY, LIMIT, WINDOW);

            assertThat(result).isTrue();
            double blocked = registry.get(RedisRateLimiter.METRIC_BLOCKED).counter().count();
            assertThat(blocked).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should_return_true_when_redis_counter_exceeds_limit")
        void should_return_true_when_redis_counter_exceeds_limit() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(String.valueOf(LIMIT + 10));
            RedisRateLimiter limiter = rateLimiter(true);

            boolean result = limiter.isOverLimit(SCOPE, KEY, LIMIT, WINDOW);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should_return_false_when_redis_key_is_null_meaning_no_requests_yet")
        void should_return_false_when_redis_key_is_null_meaning_no_requests_yet() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null);
            RedisRateLimiter limiter = rateLimiter(true);

            boolean result = limiter.isOverLimit(SCOPE, KEY, LIMIT, WINDOW);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should_fail_open_and_increment_degraded_metric_when_redis_throws_on_opsForValue")
        void should_fail_open_and_increment_degraded_metric_when_redis_throws_on_opsForValue() {
            when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis down"));
            RedisRateLimiter limiter = rateLimiter(true);

            assertThatNoException().isThrownBy(() ->
                    limiter.isOverLimit(SCOPE, KEY, LIMIT, WINDOW));

            double degraded = registry.get(RedisRateLimiter.METRIC_DEGRADED).counter().count();
            assertThat(degraded).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should_fail_open_and_fall_through_to_L1_when_redis_throws_on_get")
        void should_fail_open_and_fall_through_to_L1_when_redis_throws_on_get() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenThrow(new RuntimeException("timeout"));
            RedisRateLimiter limiter = rateLimiter(true);

            // L1 is empty → not over limit after fail-open
            boolean result = limiter.isOverLimit(SCOPE, KEY, LIMIT, WINDOW);

            assertThat(result).isFalse();
            double degraded = registry.get(RedisRateLimiter.METRIC_DEGRADED).counter().count();
            assertThat(degraded).isEqualTo(1.0);
        }
    }

    // ═══════════════ increment ═══════════════

    @Nested
    @DisplayName("increment")
    class IncrementTests {

        @Test
        @DisplayName("should_invoke_lua_script_via_execute_when_redis_enabled")
        void should_invoke_lua_script_via_execute_when_redis_enabled() {
            when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);
            RedisRateLimiter limiter = rateLimiter(true);

            long count = limiter.increment(SCOPE, KEY, WINDOW);

            assertThat(count).isEqualTo(1L);
            verify(redisTemplate).execute(any(RedisScript.class), anyList(), any());
        }

        @Test
        @DisplayName("should_pass_window_seconds_as_argv_to_lua_script")
        void should_pass_window_seconds_as_argv_to_lua_script() {
            when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(2L);
            RedisRateLimiter limiter = rateLimiter(true);

            long count = limiter.increment(SCOPE, KEY, Duration.ofSeconds(30));

            assertThat(count).isEqualTo(2L);
        }

        @Test
        @DisplayName("should_fall_through_to_L1_and_not_throw_when_redis_execute_throws")
        void should_fall_through_to_L1_and_not_throw_when_redis_execute_throws() {
            when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                    .thenThrow(new RuntimeException("Redis down"));
            RedisRateLimiter limiter = rateLimiter(true);

            assertThatNoException().isThrownBy(() -> limiter.increment(SCOPE, KEY, WINDOW));
            double degraded = registry.get(RedisRateLimiter.METRIC_DEGRADED).counter().count();
            assertThat(degraded).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should_use_L1_counter_directly_when_redis_disabled")
        void should_use_L1_counter_directly_when_redis_disabled() {
            RedisRateLimiter limiter = rateLimiter(false);

            long first = limiter.increment(SCOPE, KEY, WINDOW);
            long second = limiter.increment(SCOPE, KEY, WINDOW);

            assertThat(first).isEqualTo(1L);
            assertThat(second).isEqualTo(2L);
            verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
        }

        @Test
        @DisplayName("should_use_L1_counter_when_redis_execute_returns_null")
        void should_use_L1_counter_when_redis_execute_returns_null() {
            when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(null);
            RedisRateLimiter limiter = rateLimiter(true);

            // null from execute → fall through to L1
            long count = limiter.increment(SCOPE, KEY, WINDOW);

            assertThat(count).isEqualTo(1L);
        }
    }

    // ═══════════════ incrementAndCheck ═══════════════

    @Nested
    @DisplayName("incrementAndCheck")
    class IncrementAndCheckTests {

        @Test
        @DisplayName("should_return_false_when_counter_after_increment_is_within_limit")
        void should_return_false_when_counter_after_increment_is_within_limit() {
            when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(3L);
            RedisRateLimiter limiter = rateLimiter(true);

            boolean over = limiter.incrementAndCheck(SCOPE, KEY, LIMIT, WINDOW);

            assertThat(over).isFalse();
        }

        @Test
        @DisplayName("should_return_true_and_increment_blocked_metric_when_counter_exceeds_limit")
        void should_return_true_and_increment_blocked_metric_when_counter_exceeds_limit() {
            when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn((long) LIMIT + 1);
            RedisRateLimiter limiter = rateLimiter(true);

            boolean over = limiter.incrementAndCheck(SCOPE, KEY, LIMIT, WINDOW);

            assertThat(over).isTrue();
            double blocked = registry.get(RedisRateLimiter.METRIC_BLOCKED).counter().count();
            assertThat(blocked).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should_return_false_when_redis_disabled_and_L1_count_within_limit")
        void should_return_false_when_redis_disabled_and_L1_count_within_limit() {
            RedisRateLimiter limiter = rateLimiter(false);

            boolean result = limiter.incrementAndCheck(SCOPE, KEY, LIMIT, WINDOW);

            assertThat(result).isFalse();
        }
    }

    // ═══════════════ reset ═══════════════

    @Nested
    @DisplayName("reset")
    class ResetTests {

        @Test
        @DisplayName("should_not_call_redis_delete_when_redis_disabled")
        void should_not_call_redis_delete_when_redis_disabled() {
            RedisRateLimiter limiter = rateLimiter(false);

            limiter.reset(SCOPE, KEY);

            verify(redisTemplate, never()).delete(anyString());
        }

        @Test
        @DisplayName("should_call_redis_delete_with_correct_key_when_redis_enabled")
        void should_call_redis_delete_with_correct_key_when_redis_enabled() {
            RedisRateLimiter limiter = rateLimiter(true);

            assertThatNoException().isThrownBy(() -> limiter.reset(SCOPE, KEY));

            verify(redisTemplate).delete(anyString());
        }

        @Test
        @DisplayName("should_clear_L1_counter_even_when_redis_delete_throws")
        void should_clear_L1_counter_even_when_redis_delete_throws() {
            when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("Redis down"));
            RedisRateLimiter limiter = rateLimiter(true);

            // First increment to L1 (via fail-open from broken execute)
            when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                    .thenThrow(new RuntimeException("Redis down"));
            limiter.increment(SCOPE, KEY, WINDOW);
            limiter.increment(SCOPE, KEY, WINDOW);

            // reset should not throw, L1 should be cleared
            assertThatNoException().isThrownBy(() -> limiter.reset(SCOPE, KEY));

            // after reset, L1 isOverLimit = false (counter was cleared)
            boolean over = limiter.isOverLimit(SCOPE, KEY, 1, WINDOW);
            assertThat(over).isFalse();
        }

        @Test
        @DisplayName("should_clear_L1_counter_when_redis_disabled_so_subsequent_check_returns_false")
        void should_clear_L1_counter_when_redis_disabled_so_subsequent_check_returns_false() {
            RedisRateLimiter limiter = rateLimiter(false);

            // Increment past the limit into L1
            for (int i = 0; i < LIMIT + 2; i++) {
                limiter.increment(SCOPE, KEY, WINDOW);
            }
            assertThat(limiter.isOverLimit(SCOPE, KEY, LIMIT, WINDOW)).isTrue();

            // act
            limiter.reset(SCOPE, KEY);

            // assert: L1 cleared → not over limit anymore
            assertThat(limiter.isOverLimit(SCOPE, KEY, LIMIT, WINDOW)).isFalse();
        }
    }

    // ═══════════════ L1 windowed counter ═══════════════

    @Nested
    @DisplayName("L1 windowed counter (redis disabled)")
    class L1WindowedCounterTests {

        @Test
        @DisplayName("should_enforce_limit_in_L1_after_enough_increments_when_redis_disabled")
        void should_enforce_limit_in_L1_after_enough_increments_when_redis_disabled() {
            RedisRateLimiter limiter = rateLimiter(false);
            int testLimit = 3;

            for (int i = 0; i < testLimit + 1; i++) {
                limiter.increment(SCOPE, KEY, WINDOW);
            }

            assertThat(limiter.isOverLimit(SCOPE, KEY, testLimit, WINDOW)).isTrue();
        }

        @Test
        @DisplayName("should_not_be_over_limit_in_L1_before_reaching_threshold_when_redis_disabled")
        void should_not_be_over_limit_in_L1_before_reaching_threshold_when_redis_disabled() {
            RedisRateLimiter limiter = rateLimiter(false);
            int testLimit = 5;

            for (int i = 0; i < testLimit - 1; i++) {
                limiter.increment(SCOPE, KEY, WINDOW);
            }

            assertThat(limiter.isOverLimit(SCOPE, KEY, testLimit, WINDOW)).isFalse();
        }

        @Test
        @DisplayName("should_count_independently_for_different_keys_in_L1")
        void should_count_independently_for_different_keys_in_L1() {
            RedisRateLimiter limiter = rateLimiter(false);
            int testLimit = 3;

            for (int i = 0; i < testLimit + 1; i++) {
                limiter.increment(SCOPE, "key-A", WINDOW);
            }

            // key-B has zero increments
            assertThat(limiter.isOverLimit(SCOPE, "key-B", testLimit, WINDOW)).isFalse();
            // key-A is over
            assertThat(limiter.isOverLimit(SCOPE, "key-A", testLimit, WINDOW)).isTrue();
        }

        @Test
        @DisplayName("should_degrade_to_L1_and_enforce_limit_when_redis_is_down")
        void should_degrade_to_L1_and_enforce_limit_when_redis_is_down() {
            // Redis throws on every call → fail-open, count in L1
            when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                    .thenThrow(new RuntimeException("Redis down"));
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis down"));

            RedisRateLimiter limiter = rateLimiter(true);
            int testLimit = 3;

            for (int i = 0; i < testLimit + 1; i++) {
                limiter.increment(SCOPE, KEY, WINDOW);
            }

            // L1 catches the breach
            assertThat(limiter.isOverLimit(SCOPE, KEY, testLimit, WINDOW)).isTrue();
        }
    }

    // ═══════════════ Redis key naming ═══════════════

    @Nested
    @DisplayName("Redis key naming")
    class RedisKeyTests {

        @Test
        @DisplayName("should_use_key_prefix_and_scope_in_the_redis_key_passed_to_execute")
        void should_use_key_prefix_and_scope_in_the_redis_key_passed_to_execute() {
            when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);
            RedisRateLimiter limiter = rateLimiter(true);

            limiter.increment(SCOPE, KEY, WINDOW);

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<List<String>> keysCaptor =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            verify(redisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), any());
            String usedKey = keysCaptor.getValue().get(0);
            assertThat(usedKey).startsWith("pc:rl:" + SCOPE + ":");
        }
    }
}

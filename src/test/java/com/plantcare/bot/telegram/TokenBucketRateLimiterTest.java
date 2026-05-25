package com.plantcare.bot.telegram;

import com.plantcare.bot.config.TelegramRateLimitProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты {@link TokenBucketRateLimiter} (issue #29) на детерминированном,
 * вручную сдвигаемом {@link Clock}. Никаких реальных пауз и реального времени —
 * счётчик токенов проверяется по математике пополнения.
 */
@DisplayName("TokenBucketRateLimiter — детерминированный token bucket (issue #29)")
class TokenBucketRateLimiterTest {

    private static final int PERMITS_PER_SECOND = 5;

    private MutableClock clock;
    private TokenBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-05-25T10:00:00Z"));
        TelegramRateLimitProperties properties = new TelegramRateLimitProperties(
                PERMITS_PER_SECOND, 100, 3, 1L, 60L);
        limiter = new TokenBucketRateLimiter(properties, clock);
    }

    @Test
    @DisplayName("should_return_zero_wait_for_first_burst_within_one_second")
    void should_return_zero_wait_for_first_burst_within_one_second() {
        // arrange: ведро стартует полным (= permitsPerSecond), время не двигаем.

        // act + assert: первые N permit'ов уходят мгновенно.
        for (int i = 0; i < PERMITS_PER_SECOND; i++) {
            assertThat(limiter.reserveNextPermitMillis())
                    .as("permit #%d должен быть доступен немедленно", i + 1)
                    .isZero();
        }
    }

    @Test
    @DisplayName("should_return_positive_wait_when_bucket_exhausted_in_same_second")
    void should_return_positive_wait_when_bucket_exhausted_in_same_second() {
        // arrange: исчерпываем весь стартовый запас в тот же момент времени.
        for (int i = 0; i < PERMITS_PER_SECOND; i++) {
            limiter.reserveNextPermitMillis();
        }

        // act: (N+1)-й permit в ту же секунду.
        long wait = limiter.reserveNextPermitMillis();

        // assert: ждать придётся ~ до накопления одного токена = 1000/permitsPerSecond.
        assertThat(wait)
                .isPositive()
                .isLessThanOrEqualTo(1000L / PERMITS_PER_SECOND);
    }

    @Test
    @DisplayName("should_return_zero_wait_again_after_clock_advanced_past_refill_window")
    void should_return_zero_wait_again_after_clock_advanced_past_refill_window() {
        // arrange: исчерпали стартовый запас.
        for (int i = 0; i < PERMITS_PER_SECOND; i++) {
            limiter.reserveNextPermitMillis();
        }
        assertThat(limiter.reserveNextPermitMillis())
                .as("сразу после исчерпания запаса ждём положительную паузу")
                .isPositive();

        // act: сдвигаем часы на секунду вперёд — ведро успело пополниться.
        clock.advanceMillis(1_000);

        // assert: токен снова доступен немедленно.
        assertThat(limiter.reserveNextPermitMillis())
                .as("после пополнения за 1с permit снова мгновенный")
                .isZero();
    }

    @Test
    @DisplayName("should_cap_refill_at_capacity_when_idle_for_long")
    void should_cap_refill_at_capacity_when_idle_for_long() {
        // arrange: один permit, затем долгий простой (10 секунд).
        limiter.reserveNextPermitMillis();
        clock.advanceMillis(10_000);

        // act + assert: ведро не накопит больше capacity=permitsPerSecond токенов,
        // значит мгновенных permit'ов будет ровно permitsPerSecond, не больше.
        for (int i = 0; i < PERMITS_PER_SECOND; i++) {
            assertThat(limiter.reserveNextPermitMillis())
                    .as("permit #%d после простоя должен быть мгновенным (в пределах capacity)", i + 1)
                    .isZero();
        }

        assertThat(limiter.reserveNextPermitMillis())
                .as("permit сверх capacity уже требует ожидания — накопление не безгранично")
                .isPositive();
    }

    @Test
    @DisplayName("should_treat_permits_per_second_below_one_as_one")
    void should_treat_permits_per_second_below_one_as_one() {
        // arrange: некорректная конфигурация (0) не должна ронять делением/циклом.
        TelegramRateLimitProperties zeroProps = new TelegramRateLimitProperties(0, 100, 3, 1L, 60L);
        TokenBucketRateLimiter zeroLimiter = new TokenBucketRateLimiter(zeroProps, clock);

        // act: один permit уходит сразу (capacity подтянут к минимуму 1).
        long first = zeroLimiter.reserveNextPermitMillis();
        long second = zeroLimiter.reserveNextPermitMillis();

        // assert: первый мгновенно, второй ждёт ~секунду (1 permit/sec).
        assertThat(first).isZero();
        assertThat(second).isPositive().isLessThanOrEqualTo(1000L);
    }

    /**
     * Сдвигаемый Clock: лимитер читает только {@link #millis()}/{@link #instant()},
     * поэтому достаточно подменять текущий момент между вызовами.
     */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advanceMillis(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public long millis() {
            return now.toEpochMilli();
        }
    }
}

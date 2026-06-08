package com.plantcare.core.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.plantcare.core.config.RedisProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Issue #280, эпик #277 фаза 2: распределённый fixed-window rate-limit на Redis.
 *
 * <p>Заменяет per-instance Caffeine-счётчики (issue #92): при нескольких инстансах
 * приложения локальный счётчик давал реальный лимит ×N. Здесь счётчик общий — лежит
 * в Redis, инкрементируется атомарно Lua-скриптом (INCR + EXPIRE одной командой,
 * без гонки между INCR и установкой TTL).
 *
 * <p><b>Подход:</b> Lua-скрипт поверх уже подключённого Lettuce
 * ({@code spring-boot-starter-data-redis}). Никаких bucket4j/redisson — лишних
 * зависимостей в pom нет (решение владельца).
 *
 * <p><b>Деградация — fail-open.</b> Для plant-care работоспособность важнее защиты
 * (DDoS — не основная угроза). Если Redis недоступен/таймаут/ошибка:
 * <ul>
 *   <li>трафик <b>пропускается</b> (запрос не блокируется);</li>
 *   <li>счёт ведётся локально через Caffeine L1, чтобы лимит хотя бы per-instance
 *       продолжал работать в деградированном режиме;</li>
 *   <li>переход в degraded логируется и считается метрикой
 *       {@value #METRIC_DEGRADED} (тег {@code reason}).</li>
 * </ul>
 *
 * <p>Лежит в {@code core}, чтобы и {@code api}, и {@code admin} могли использовать
 * общий счётчик, не нарушая границы модульного монолита (api ∌ admin/bot).
 */
@Slf4j
@Component
public class RedisRateLimiter {

    /** Метрика переходов в degraded-режим (Redis недоступен). Тег {@code reason}. */
    public static final String METRIC_DEGRADED = "ratelimit.redis.degraded";
    /** Имя счётчика срабатываний лимита. Теги {@code scope}, {@code source}. */
    public static final String METRIC_BLOCKED = "ratelimit.blocked";

    private static final String KEY_INFIX = "rl:";

    /**
     * Атомарный fixed-window: INCR ключа, на первом инкременте ставим EXPIRE на окно.
     * Возвращает текущее значение счётчика. TTL ставится ровно один раз за окно,
     * поэтому окно «скользит» по первому запросу, а не сбрасывается на каждом INCR.
     */
    private static final RedisScript<Long> INCR_AND_EXPIRE = new DefaultRedisScript<>(
            """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """,
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisProperties redisProperties;
    private final RateLimitProperties rateLimitProperties;
    private final MeterRegistry meterRegistry;

    /** Локальный fallback-счётчик (L1). Значение — счётчик в окне; TTL = окно. */
    private final Cache<String, AtomicInteger> l1;

    /**
     * Текущий режим: {@code true} = Redis считается недоступным, идём в L1.
     * Не «жёсткий» circuit breaker — просто чтобы не логировать degraded на каждый
     * запрос подряд (логируем только на переходе).
     */
    private final AtomicLong lastDegradedLogEpoch = new AtomicLong(0);

    public RedisRateLimiter(StringRedisTemplate redisTemplate,
                            RedisProperties redisProperties,
                            RateLimitProperties rateLimitProperties,
                            MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.redisProperties = redisProperties;
        this.rateLimitProperties = rateLimitProperties;
        this.meterRegistry = meterRegistry;
        this.l1 = Caffeine.newBuilder()
                .maximumSize(rateLimitProperties.l1MaxKeys())
                .build();
    }

    /**
     * Проверяет, не превышен ли лимит для ключа в текущем окне — БЕЗ инкремента.
     *
     * <p>Для admin-логина (счёт неудач) важно разделять «проверить» и «записать»:
     * фильтр проверяет до BCrypt, инкремент делает failure-handler. Поэтому
     * {@code isOverLimit} читает счётчик (GET), не трогая его.
     *
     * @param scope  низкокардинальный ярлык точки вызова (для метрик/логов)
     * @param key    идентификатор субъекта (IP, email, …)
     * @param limit  максимум запросов в окне
     * @param window длина окна
     * @return {@code true} если лимит уже исчерпан (надо блокировать)
     */
    public boolean isOverLimit(String scope, String key, int limit, Duration window) {
        if (rateLimitProperties.redisEnabled()) {
            try {
                String raw = redisTemplate.opsForValue().get(redisKey(scope, key));
                long current = raw == null ? 0L : Long.parseLong(raw);
                boolean over = current >= limit;
                if (over) {
                    recordBlocked(scope, "redis");
                }
                return over;
            } catch (Exception e) {
                degraded(scope, e);
                // fall through to L1
            }
        }
        return l1IsOverLimit(scope, key, limit);
    }

    /**
     * Атомарно инкрементирует счётчик ключа в текущем окне и возвращает, был ли
     * лимит уже исчерпан ПЕРЕД этим инкрементом.
     *
     * <p>Возвращаемое значение — «этот запрос уже за пределом?»: {@code true}, если
     * счётчик после инкремента превысил лимит. Точки вызова, которые сами решают
     * блокировать (magic-link/guest), могут использовать это как «исчерпан?».
     *
     * @return {@code true} если после инкремента лимит превышен
     */
    public boolean incrementAndCheck(String scope, String key, int limit, Duration window) {
        long current = increment(scope, key, window);
        boolean over = current > limit;
        if (over) {
            recordBlocked(scope, rateLimitProperties.redisEnabled() ? "redis" : "l1");
        }
        return over;
    }

    /**
     * Инкрементирует счётчик ключа в текущем окне, возвращает новое значение.
     * Fail-open: при ошибке Redis считает в L1 и не бросает исключений.
     */
    public long increment(String scope, String key, Duration window) {
        if (rateLimitProperties.redisEnabled()) {
            try {
                Long current = redisTemplate.execute(
                        INCR_AND_EXPIRE,
                        List.of(redisKey(scope, key)),
                        Long.toString(Math.max(1, window.toSeconds())));
                if (current != null) {
                    return current;
                }
            } catch (Exception e) {
                degraded(scope, e);
                // fall through to L1
            }
        }
        return l1Increment(scope, key, window);
    }

    /**
     * Сбрасывает счётчик ключа (например, успешный admin-логин обнуляет неудачи).
     * Fail-open: ошибка Redis не мешает — L1 чистим всегда.
     */
    public void reset(String scope, String key) {
        if (rateLimitProperties.redisEnabled()) {
            try {
                redisTemplate.delete(redisKey(scope, key));
            } catch (Exception e) {
                degraded(scope, e);
            }
        }
        l1.invalidate(l1Key(scope, key));
    }

    // ──────────────── L1 (Caffeine) fallback ────────────────

    private boolean l1IsOverLimit(String scope, String key, int limit) {
        AtomicInteger c = l1.getIfPresent(l1Key(scope, key));
        if (!(c instanceof WindowedCounter wc) || wc.isExpired()) {
            return false;
        }
        return wc.get() >= limit;
    }

    private long l1Increment(String scope, String key, Duration window) {
        // Окно эмулируем вручную (Caffeine не даёт per-entry TTL без отдельной policy):
        // храним счётчик с отметкой начала окна и пересоздаём при истечении.
        // Для fallback-режима (Redis недоступен) достаточно грубого per-instance лимита.
        return l1Windowed(scope, key, window).incrementAndGet();
    }

    private AtomicInteger l1Windowed(String scope, String key, Duration window) {
        return l1.asMap().compute(l1Key(scope, key), (k, existing) -> {
            if (existing instanceof WindowedCounter wc && !wc.isExpired()) {
                return wc;
            }
            return new WindowedCounter(window.toNanos());
        });
    }

    /** Счётчик с отметкой начала окна (для ручного истечения в L1-режиме). */
    private static final class WindowedCounter extends AtomicInteger {
        private final long startNanos;
        private final long windowNanos;

        private WindowedCounter(long windowNanos) {
            super(0);
            this.startNanos = System.nanoTime();
            this.windowNanos = windowNanos;
        }

        private boolean isExpired() {
            return (System.nanoTime() - startNanos) >= windowNanos;
        }
    }

    // ──────────────── helpers ────────────────

    private String redisKey(String scope, String key) {
        return redisProperties.keyPrefix() + KEY_INFIX + scope + ":" + key;
    }

    private String l1Key(String scope, String key) {
        return scope + ":" + key;
    }

    private void recordBlocked(String scope, String source) {
        try {
            meterRegistry.counter(METRIC_BLOCKED, "scope", scope, "source", source).increment();
        } catch (Exception ignored) {
            // метрика не должна влиять на основной поток
        }
    }

    private void degraded(String scope, Exception e) {
        try {
            meterRegistry.counter(METRIC_DEGRADED, "reason", e.getClass().getSimpleName()).increment();
        } catch (Exception ignored) {
            // noop
        }
        // Логируем не на каждый запрос (Redis может быть down долго) — раз в ~30 секунд.
        long now = System.currentTimeMillis();
        long last = lastDegradedLogEpoch.get();
        if (now - last > 30_000 && lastDegradedLogEpoch.compareAndSet(last, now)) {
            log.warn("Rate-limit DEGRADED (fail-open + Caffeine L1) for scope={}: Redis unavailable: {}",
                    scope, e.getMessage());
        }
    }
}

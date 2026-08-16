package com.plantcare.api.auth.service;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import com.plantcare.api.config.TelegramAuthProperties;
import com.plantcare.core.config.RedisProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Issue #318: хранилище сессий входа существующих Telegram-юзеров.
 *
 * <p>Бэкенд — Redis (родной TTL для одноразовой короткоживущей сессии). Ключ —
 * {@code pc:tg-auth-session:<sessionId>}; значение — {@link TelegramAuthSession},
 * сериализованный в JSON-строку собственным {@link ObjectMapper} и положенный
 * через {@link StringRedisTemplate}. Свой mapper (а не общий
 * {@code RedisTemplate<String,Object>} с default-typing) — чтобы round-trip
 * record-а был детерминированным: default-typing не добавляет {@code @class}
 * для финальных типов (record финален), и значение восстановилось бы как
 * {@code LinkedHashMap}, а не как сессия.
 *
 * <p>Истечение обеспечивает Redis (TTL = {@code sessionTtl}); код одноразовый
 * (удаляется при успешном verify), счётчик попыток обновляется перезаписью с
 * сохранением остаточного TTL.
 *
 * <p>Redis здесь — единственный источник правды по сессии. При недоступном Redis
 * операции бросают исключение, и start/verify завершаются ошибкой — приемлемо для
 * редкого флоу входа (graceful: клиент получит ошибку, не «молчаливый» успех).
 */
@Slf4j
@Component
public class TelegramAuthSessionStore {

    private static final String PREFIX = "tg-auth-session:";

    private final StringRedisTemplate redisTemplate;
    private final RedisProperties redisProperties;
    private final TelegramAuthProperties telegramAuthProperties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public TelegramAuthSessionStore(StringRedisTemplate redisTemplate,
                                    RedisProperties redisProperties,
                                    TelegramAuthProperties telegramAuthProperties,
                                    Clock clock) {
        this.redisTemplate = redisTemplate;
        this.redisProperties = redisProperties;
        this.telegramAuthProperties = telegramAuthProperties;
        this.clock = clock;
        // Jackson 3: ObjectMapper неизменяем — конфигурируем через builder.
        // java.time встроен в databind, отдельный JavaTimeModule не нужен;
        // WRITE_DATES_AS_TIMESTAMPS переехал в DateTimeFeature.
        this.objectMapper = JsonMapper.builder()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /** Создаёт новую (ещё не привязанную к коду) сессию с TTL = {@code sessionTtl}. */
    public TelegramAuthSession create(String sessionId) {
        Duration ttl = telegramAuthProperties.sessionTtl();
        var session = new TelegramAuthSession(sessionId, null, null, 0, clock.instant().plus(ttl));
        redisTemplate.opsForValue().set(key(sessionId), serialize(session), ttl);
        return session;
    }

    /** Возвращает сессию, если она ещё жива в Redis (иначе — истекла/не существует). */
    public Optional<TelegramAuthSession> find(String sessionId) {
        String raw = redisTemplate.opsForValue().get(key(sessionId));
        if (raw == null) {
            return Optional.empty();
        }
        return Optional.of(deserialize(raw));
    }

    /**
     * Сохраняет обновлённую сессию, СОХРАНЯЯ остаточный TTL ключа (истечение не
     * продлевается обновлением — окно отсчитывается от {@code create}).
     */
    public void save(TelegramAuthSession session) {
        Long remaining = redisTemplate.getExpire(key(session.sessionId()));
        Duration ttl = (remaining != null && remaining > 0)
                ? Duration.ofSeconds(remaining)
                : telegramAuthProperties.sessionTtl();
        redisTemplate.opsForValue().set(key(session.sessionId()), serialize(session), ttl);
    }

    /** Удаляет сессию (одноразовость: после успешного входа или гашения по попыткам). */
    public void delete(String sessionId) {
        redisTemplate.delete(key(sessionId));
    }

    private String serialize(TelegramAuthSession session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Telegram auth session", e);
        }
    }

    private TelegramAuthSession deserialize(String raw) {
        try {
            return objectMapper.readValue(raw, TelegramAuthSession.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize Telegram auth session", e);
        }
    }

    private String key(String sessionId) {
        return redisProperties.keyPrefix() + PREFIX + sessionId;
    }
}

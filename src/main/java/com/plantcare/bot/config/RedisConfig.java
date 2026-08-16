package com.plantcare.bot.config;

import com.plantcare.core.config.RedisProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Issue #278, эпик #277 фаза 0: конфигурация Redis (Lettuce).
 *
 * <p>Подключение настраивается через {@code spring.data.redis.url} (из env {@code REDIS_URL})
 * или через отдельные host/port-параметры для локального dev-профиля.
 *
 * <p>Принципы:
 * <ul>
 *   <li>Lettuce — неблокирующий клиент; shared connection thread-safe.</li>
 *   <li>Сериализация значений через {@link GenericJacksonJsonRedisSerializer} с type info.</li>
 *   <li>Ключи — строки с префиксом {@code pc:} (задаётся в {@link RedisProperties#keyPrefix()}).</li>
 *   <li>Таймауты подключения/команды настраиваются из {@link RedisProperties}.</li>
 *   <li>Redis НЕ является жёстким пререквизитом для старта (graceful degradation).</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RedisProperties.class)
public class RedisConfig {

    /**
     * Настраивает Lettuce-клиент: таймауты подключения и команд.
     *
     * <p>Короткие таймауты (connect: 2 s, command: 1 s) обеспечивают быстрый fail-fast
     * при недоступном Redis — приложение не зависает на старте, деградирует по фазам.
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer lettuceClientConfigurationCustomizer(
            RedisProperties redisProperties) {

        return builder -> builder.clientOptions(
                ClientOptions.builder()
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(redisProperties.connectTimeout())
                                .build())
                        .timeoutOptions(TimeoutOptions.enabled(redisProperties.commandTimeout()))
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .autoReconnect(true)
                        .build()
        );
    }

    /**
     * Настраивает {@link ObjectMapper} для Redis-сериализации с поддержкой type info.
     *
     * <p>Type info необходима для корректной десериализации полиморфных типов при
     * восстановлении данных из Redis (иначе Jackson не знает в какой класс маппить).
     */
    // НЕ @Bean: бин типа ObjectMapper подавляет авто-конфигурируемый Spring Boot
    // ObjectMapper (@ConditionalOnMissingBean(ObjectMapper.class)) и становится основным
    // для Spring MVC. Тогда default-typing (@class) протекает в HTTP: тела POST/PUT/PATCH
    // не парсятся («Malformed request body»), ответы засоряются @class. Этот mapper —
    // ТОЛЬКО для Redis-сериализации, создаётся локально для redisTemplate().
    private ObjectMapper redisObjectMapper() {
        // Jackson 3: ObjectMapper неизменяем, конфигурация только через builder.
        // JavaTimeModule больше не нужен — поддержка java.time встроена в databind,
        // а WRITE_DATES_AS_TIMESTAMPS переехал из SerializationFeature в DateTimeFeature.
        return JsonMapper.builder()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // Включаем type info: без этого GenericJacksonJsonRedisSerializer
                // не сможет корректно десериализовать значения обратно в конкретный тип.
                // Jackson 3 больше не отдаёт публичный LaissezFaire-валидатор (он package-private),
                // поэтому явно разрешаем любой подтип — это ровно то поведение, что было
                // на Jackson 2. Безопасно: в этот Redis пишем только мы сами, чужой JSON
                // сюда не попадает, а mapper используется исключительно для Redis-значений.
                .activateDefaultTyping(
                        BasicPolymorphicTypeValidator.builder().allowIfSubType(Object.class).build(),
                        DefaultTyping.NON_FINAL,
                        JsonTypeInfo.As.PROPERTY
                )
                .build();
    }

    /**
     * Основной {@link RedisTemplate} для работы с Redis.
     *
     * <ul>
     *   <li>Ключи: строки (UTF-8).</li>
     *   <li>Значения: JSON с type info через {@link GenericJacksonJsonRedisSerializer}.</li>
     *   <li>Hash-ключи и hash-значения — аналогично.</li>
     * </ul>
     *
     * <p>Namespacing ключей (префикс {@code pc:}) реализуется на уровне сервисов-потребителей
     * через {@link RedisProperties#keyPrefix()}, а не на уровне шаблона,
     * чтобы не ломать Spring Data Redis {@code @Cacheable} и прочие механизмы.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory) {

        var template = new RedisTemplate<String, Object>();
        template.setConnectionFactory(connectionFactory);

        var stringSerializer = new StringRedisSerializer();
        var jsonSerializer = new GenericJacksonJsonRedisSerializer(redisObjectMapper());

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.setDefaultSerializer(jsonSerializer);
        template.afterPropertiesSet();

        log.info("RedisTemplate initialized with GenericJacksonJsonRedisSerializer");
        return template;
    }
}

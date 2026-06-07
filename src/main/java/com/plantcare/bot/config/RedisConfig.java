package com.plantcare.bot.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
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
 *   <li>Сериализация значений через {@link GenericJackson2JsonRedisSerializer} с type info.</li>
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
    @Bean("redisObjectMapper")
    public ObjectMapper redisObjectMapper() {
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // Включаем type info: без этого GenericJackson2JsonRedisSerializer
        // не сможет корректно десериализовать значения обратно в конкретный тип.
        mapper.activateDefaultTyping(
                mapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );
        return mapper;
    }

    /**
     * Основной {@link RedisTemplate} для работы с Redis.
     *
     * <ul>
     *   <li>Ключи: строки (UTF-8).</li>
     *   <li>Значения: JSON с type info через {@link GenericJackson2JsonRedisSerializer}.</li>
     *   <li>Hash-ключи и hash-значения — аналогично.</li>
     * </ul>
     *
     * <p>Namespacing ключей (префикс {@code pc:}) реализуется на уровне сервисов-потребителей
     * через {@link RedisProperties#keyPrefix()}, а не на уровне шаблона,
     * чтобы не ломать Spring Data Redis {@code @Cacheable} и прочие механизмы.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory,
            ObjectMapper redisObjectMapper) {

        var template = new RedisTemplate<String, Object>();
        template.setConnectionFactory(connectionFactory);

        var stringSerializer = new StringRedisSerializer();
        var jsonSerializer = new GenericJackson2JsonRedisSerializer(redisObjectMapper);

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.setDefaultSerializer(jsonSerializer);
        template.afterPropertiesSet();

        log.info("RedisTemplate initialized with GenericJackson2JsonRedisSerializer");
        return template;
    }
}

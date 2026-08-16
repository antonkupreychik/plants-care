package com.plantcare.bot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регресс на #281 (эпик #277 фаза 3): {@code redisObjectMapper} НЕ должен подменять
 * основной Spring MVC {@link ObjectMapper}.
 *
 * <p>До фикса {@code redisObjectMapper} был объявлен как {@code @Bean ObjectMapper} и
 * подавлял авто-конфигурируемый Spring Boot mapper ({@code @ConditionalOnMissingBean}),
 * становясь основным. Его {@code activateDefaultTyping(NON_FINAL)} протекал в HTTP:
 * тела POST/PUT/PATCH не парсились («Malformed request body»), а ответы засорялись
 * {@code @class} / типизированными коллекциями ({@code ["java.util...ListN",[...]]}).
 */
class PrimaryObjectMapperTest extends IntegrationTestBase {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void should_serialize_collections_without_type_info_in_primary_mapper() throws Exception {
        // arrange / act
        String json = objectMapper.writeValueAsString(List.of("a", "b"));

        // assert — чистый JSON-массив, НЕ ["java.util.ImmutableCollections$ListN",[...]]
        assertThat(json).isEqualTo("[\"a\",\"b\"]");
        assertThat(json).doesNotContain("@class");
    }
}

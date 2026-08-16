package com.plantcare.api.smoke;

import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-context HTTP smoke (регресс-слой для класса #281/#307).
 *
 * <p>Прогоняет реальный request/response-путь Spring MVC в ПОЛНОМ контексте
 * приложения (через {@link IntegrationTestBase}: загружается {@code RedisConfig} и весь
 * wiring, как в проде; Postgres + Redis — Testcontainers). Это ловит wiring- и
 * сериализационные регрессии, которые невидимы слайс-тестам {@code @WebMvcTest} —
 * те поднимают только web-слой с чистым авто-конфигурируемым {@code ObjectMapper}.
 *
 * <p>Конкретно ловит подмену основного {@code ObjectMapper} (баг #281: бин
 * {@code redisObjectMapper} с default-typing становился MVC-маппером):
 * <ul>
 *   <li>POST с JSON-телом не должен падать «Malformed request body»;</li>
 *   <li>ответы не должны засоряться {@code @class} / type-info Redis-сериализатора.</li>
 * </ul>
 * До #307 оба теста упали бы; сейчас — зелёные.
 */
@AutoConfigureMockMvc
class ApiHttpSmokeIT extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void should_parse_json_body_and_return_clean_response_on_post() throws Exception {
        // arrange — публичный POST с телом (guest, permitAll); новый deviceId → 200 + токены
        String deviceId = UUID.randomUUID().toString();

        // act / assert
        mockMvc.perform(post("/api/v1/auth/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.isNewUser").value(true))
                // тело распарсилось (не «Malformed request body») и ответ чист от type-info
                .andExpect(content().string(not(containsString("@class"))));
    }

    @Test
    void should_return_clean_json_without_type_info_on_cached_read() throws Exception {
        // arrange / act — кэшируемый справочник (diseases, permitAll): проходит через Redis L2
        // assert — чистый REST-JSON, без @class и без typed-collection обёрток
        mockMvc.perform(get("/api/v1/diseases").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(content().string(not(containsString("@class"))))
                .andExpect(content().string(not(containsString("ImmutableCollections"))));
    }
}

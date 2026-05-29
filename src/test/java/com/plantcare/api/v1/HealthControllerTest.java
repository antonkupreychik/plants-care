package com.plantcare.api.v1;

import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Интеграционный тест liveness-эндпоинта {@code GET /api/v1/health}.
 *
 * <p>Phase 0 (issue #84): эндпоинт публичный, должен возвращать 200 OK с {@code {"status":"UP"}}
 * без авторизации.
 */
@AutoConfigureMockMvc
class HealthControllerTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void should_return_200_with_status_up_when_health_endpoint_called() throws Exception {
        // act + assert
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void should_be_accessible_without_authentication() throws Exception {
        // arrange — запрос без Authorization-заголовка и без security-контекста

        // act
        MvcResult result = mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andReturn();

        // assert
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"status\":\"UP\"");
    }

    @Test
    void should_expose_openapi_json_at_v3_api_docs() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.info.title").value("Plants Care API"));
    }

    @Test
    void should_document_plant_diagnosis_path_in_api_docs() throws Exception {
        // issue #193: новый эндпоинт диагностики должен присутствовать в OpenAPI-контракте.
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/plants/{id}/diagnosis'].get").exists());
    }
}

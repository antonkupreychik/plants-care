package com.plantcare.bot.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Тесты {@link ApiExceptionHandler}.
 *
 * <p>Поднимаем настоящий web-слайс через {@link WebMvcTest} со stub-контроллером
 * {@link ApiExceptionHandlerTestStubController} (он лежит в пакете
 * {@code com.plantcare.bot.controller.api}). Advice импортируем через {@link Import},
 * без явной регистрации руками — это критично: тест должен ловить регресс, если
 * у advice удалят {@code basePackages = "com.plantcare.bot.controller.api"} (см. PR ревью).
 *
 * <p>Security-фильтры отключены ({@code addFilters = false}), чтобы
 * {@link org.springframework.security.access.AccessDeniedException} долетал до advice,
 * а не перехватывался {@code ExceptionTranslationFilter}.
 */
@WebMvcTest(controllers = ApiExceptionHandlerTestStubController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class ApiExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void should_return_400_with_validation_error_when_body_invalid() throws Exception {
        // arrange
        String invalidBody = objectMapper.writeValueAsString(
                new ApiExceptionHandlerTestStubController.DummyBody(""));

        // act + assert
        mockMvc.perform(post("/test-stub/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message").value("Validation failed"))
                .andExpect(jsonPath("$.error.details[0].field").value("name"))
                .andExpect(jsonPath("$.error.details[0].message").exists());
    }

    @Test
    void should_return_404_when_entity_not_found() throws Exception {
        // act
        MvcResult result = mockMvc.perform(get("/test-stub/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Resource not found"))
                .andExpect(jsonPath("$.error.details").value((Object) null))
                .andReturn();

        // assert — настоящее сообщение исключения наружу не должно протечь (anti-leak)
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain("plant 42")
                .doesNotContain("Unable to find");
    }

    @Test
    void should_return_403_when_access_denied() throws Exception {
        // act + assert
        mockMvc.perform(get("/test-stub/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.error.message").value("Access denied"))
                .andExpect(jsonPath("$.error.details").value((Object) null));
    }

    @Test
    void should_return_500_when_runtime_exception_thrown() throws Exception {
        // act
        MvcResult result = mockMvc.perform(get("/test-stub/runtime"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value("Internal server error"))
                .andExpect(jsonPath("$.error.details").value((Object) null))
                .andReturn();

        // assert — настоящее сообщение исключения "boom" наружу не должно протечь
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain("boom")
                .doesNotContain("StackTrace")
                .doesNotContain("Trace");
    }
}

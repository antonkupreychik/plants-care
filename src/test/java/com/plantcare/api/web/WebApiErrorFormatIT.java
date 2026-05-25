package com.plantcare.api.web;

import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #127 — гард на приоритет {@code @RestControllerAdvice} для spec-first
 * контроллеров ({@code com.plantcare.api.web}).
 *
 * <p>После переезда {@code web} стал вложен в {@code api}, и теперь в одном контексте
 * живут ДВА advice с пересекающимися basePackages:
 * <ul>
 *   <li>{@link com.plantcare.api.ApiExceptionHandler} ({@code com.plantcare.api}) — вложенный
 *       формат {@code {"error":{"code":...}}};</li>
 *   <li>{@link com.plantcare.api.web.exception.WebApiExceptionHandler} ({@code com.plantcare.api.web})
 *       — плоский формат {@code {"error":"...","message":"..."}}, помечен
 *       {@code @Order(HIGHEST_PRECEDENCE)}.</li>
 * </ul>
 *
 * <p>Слайс-тесты ({@code @WebMvcTest}) грузят по одному advice и этот конфликт НЕ ловят.
 * Этот IT поднимает ПОЛНЫЙ контекст (оба advice присутствуют) и фиксирует, что для
 * web-эндпоинта выигрывает именно плоский формат. Если снять {@code @Order} —
 * тест упадёт (тело уедет на вложенный формат {@code ApiExceptionHandler}).
 */
@AutoConfigureMockMvc
class WebApiErrorFormatIT extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("api.web 404 → плоский {\"error\":\"NOT_FOUND\"}, а не вложенный формат ApiExceptionHandler")
    void should_use_flat_web_error_format_when_species_not_found() throws Exception {
        long missingId = 999_999_999L;

        mockMvc.perform(get("/api/v1/species/" + missingId))
                .andExpect(status().isNotFound())
                // плоский формат WebApiExceptionHandler: $.error — строка.
                // Если выиграет ApiExceptionHandler, $.error станет объектом и матч строки упадёт.
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Species not found: " + missingId));
    }
}

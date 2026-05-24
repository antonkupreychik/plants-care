package com.plantcare.bot.web;

import com.plantcare.bot.web.exception.WebApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {CareTypeController.class, WebApiExceptionHandler.class})
@AutoConfigureMockMvc(addFilters = false)
class CareTypeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void should_return_all_care_types() throws Exception {
        // act + assert
        mockMvc.perform(get("/api/v1/care-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void should_return_watering_display_name() throws Exception {
        // act + assert
        mockMvc.perform(get("/api/v1/care-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'WATERING')].displayName").value("Полив"));
    }

    @Test
    void should_return_all_expected_codes() throws Exception {
        // act + assert
        mockMvc.perform(get("/api/v1/care-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'WATERING')]").exists())
                .andExpect(jsonPath("$[?(@.code == 'MISTING')]").exists())
                .andExpect(jsonPath("$[?(@.code == 'FERTILIZING')]").exists())
                .andExpect(jsonPath("$[?(@.code == 'SOIL_CHECK')]").exists());
    }

    @Test
    void should_return_correct_display_names_for_all_types() throws Exception {
        // act + assert
        mockMvc.perform(get("/api/v1/care-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'MISTING')].displayName").value("Опрыскивание"))
                .andExpect(jsonPath("$[?(@.code == 'FERTILIZING')].displayName").value("Подкормка"))
                .andExpect(jsonPath("$[?(@.code == 'SOIL_CHECK')].displayName").value("Проверка грунта"));
    }
}

package com.plantcare.api.web;

import com.plantcare.api.web.exception.WebApiExceptionHandler;
import com.plantcare.core.service.DiseaseCard;
import com.plantcare.core.service.DiseaseNotFoundException;
import com.plantcare.core.service.DiseaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {DiseasesController.class, WebApiExceptionHandler.class})
@AutoConfigureMockMvc(addFilters = false)
class DiseasesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DiseaseService diseaseService;

    // ------------------------------------------------------------------ GET /diseases

    @Test
    void should_return_page_of_diseases_when_no_query() throws Exception {
        // arrange
        var card = new DiseaseCard(1L, "Паутинный клещ", "Tetranychus urticae",
                "Крапчатость на листьях", "Обработать акарицидом", "Поддерживать влажность");
        var page = new PageImpl<>(List.of(card), PageRequest.of(0, 20), 1L);
        when(diseaseService.findPage(any())).thenReturn(page);

        // act + assert
        mockMvc.perform(get("/api/v1/diseases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].id").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Паутинный клещ"))
                .andExpect(jsonPath("$.items[0].latinName").value("Tetranychus urticae"))
                .andExpect(jsonPath("$.items[0].symptoms").value("Крапчатость на листьях"))
                .andExpect(jsonPath("$.items[0].treatment").value("Обработать акарицидом"))
                .andExpect(jsonPath("$.items[0].prevention").value("Поддерживать влажность"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.limit").value(20));

        verify(diseaseService).findPage(any());
    }

    @Test
    void should_return_empty_items_when_no_diseases_exist() throws Exception {
        // arrange
        var emptyPage = new PageImpl<DiseaseCard>(List.of(), PageRequest.of(0, 20), 0L);
        when(diseaseService.findPage(any())).thenReturn(emptyPage);

        // act + assert
        mockMvc.perform(get("/api/v1/diseases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void should_apply_pagination_parameters() throws Exception {
        // arrange
        var emptyPage = new PageImpl<DiseaseCard>(List.of(), PageRequest.of(1, 10), 15L);
        when(diseaseService.findPage(any())).thenReturn(emptyPage);

        // act + assert — offset=10, limit=10 → page=1
        mockMvc.perform(get("/api/v1/diseases").param("offset", "10").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offset").value(10))
                .andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.total").value(15));
    }

    @Test
    void should_delegate_to_search_when_q_is_provided() throws Exception {
        // arrange
        var card = new DiseaseCard(2L, "Мучнистая роса", null,
                "Белый налёт на листьях", "Фунгицид", "Проветривание");
        var page = new PageImpl<>(List.of(card), PageRequest.of(0, 20), 1L);
        when(diseaseService.searchPage(eq("роса"), any())).thenReturn(page);

        // act + assert
        mockMvc.perform(get("/api/v1/diseases").param("q", "роса"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Мучнистая роса"))
                .andExpect(jsonPath("$.items[0].latinName").isEmpty())
                .andExpect(jsonPath("$.total").value(1));

        verify(diseaseService).searchPage(eq("роса"), any());
    }

    @Test
    void should_return_all_when_q_is_blank() throws Exception {
        // arrange — пустая строка q = как без q
        var emptyPage = new PageImpl<DiseaseCard>(List.of(), PageRequest.of(0, 20), 0L);
        when(diseaseService.findPage(any())).thenReturn(emptyPage);

        // act + assert
        mockMvc.perform(get("/api/v1/diseases").param("q", "  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        verify(diseaseService).findPage(any());
    }

    // ------------------------------------------------------------------ GET /diseases/{id}

    @Test
    void should_return_disease_card_when_id_exists() throws Exception {
        // arrange
        var card = new DiseaseCard(5L, "Серая гниль", "Botrytis cinerea",
                "Серый пушистый налёт", "Удалить поражённые части", "Не переливать");
        when(diseaseService.getById(5L)).thenReturn(card);

        // act + assert
        mockMvc.perform(get("/api/v1/diseases/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.name").value("Серая гниль"))
                .andExpect(jsonPath("$.latinName").value("Botrytis cinerea"));
    }

    @Test
    void should_return_404_when_disease_not_found() throws Exception {
        // arrange
        when(diseaseService.getById(999L)).thenThrow(new DiseaseNotFoundException(999L));

        // act + assert
        mockMvc.perform(get("/api/v1/diseases/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Disease not found: 999"));
    }

    @Test
    void should_serialize_null_latin_name_when_not_present() throws Exception {
        // arrange — latinName nullable для неинфекционных проблем
        var card = new DiseaseCard(3L, "Хлороз", null,
                "Пожелтение листьях", "Внести железо", "Контролировать pH");
        when(diseaseService.getById(3L)).thenReturn(card);

        // act + assert
        mockMvc.perform(get("/api/v1/diseases/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latinName").isEmpty());
    }
}

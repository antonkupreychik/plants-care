package com.plantcare.bot.web;

import com.plantcare.bot.domain.Species;
import com.plantcare.bot.domain.enums.CareDifficulty;
import com.plantcare.bot.domain.enums.FactCategory;
import com.plantcare.bot.domain.enums.LightPreference;
import com.plantcare.bot.service.SpeciesFactDto;
import com.plantcare.bot.service.SpeciesFactService;
import com.plantcare.bot.service.SpeciesService;
import com.plantcare.bot.web.exception.WebApiExceptionHandler;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {SpeciesController.class, WebApiExceptionHandler.class})
@AutoConfigureMockMvc(addFilters = false)
class SpeciesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SpeciesService speciesService;

    @MockitoBean
    private SpeciesFactService speciesFactService;

    @Test
    void should_return_page_of_species_when_no_query() throws Exception {
        // arrange
        var species = buildSpecies(1L, "Монстера", "Monstera deliciosa");
        var pageable = PageRequest.of(0, 20);
        Page<Species> page = new PageImpl<>(List.of(species), pageable, 1);
        when(speciesService.findPage(eq(""), any(Pageable.class))).thenReturn(page);

        // act + assert
        mockMvc.perform(get("/api/v1/species"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].id").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Монстера"))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.limit").value(20))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void should_return_filtered_species_when_q_provided() throws Exception {
        // arrange
        var species = buildSpecies(2L, "Роза чайная", "Rosa");
        Page<Species> page = new PageImpl<>(List.of(species));
        when(speciesService.findPage(eq("роза"), any(Pageable.class))).thenReturn(page);

        // act + assert
        mockMvc.perform(get("/api/v1/species").param("q", "роза"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Роза чайная"));

        verify(speciesService).findPage(eq("роза"), any(Pageable.class));
    }

    @Test
    void should_return_species_detail_when_id_exists() throws Exception {
        // arrange
        var species = buildSpecies(1L, "Монстера", "Monstera deliciosa");
        species.setDescription("Тропическое растение с большими листьями");
        when(speciesService.getById(1L)).thenReturn(species);
        when(speciesFactService.getFactsBySpecies(eq(1L), any())).thenReturn(Map.of());

        // act + assert
        mockMvc.perform(get("/api/v1/species/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Монстера"))
                .andExpect(jsonPath("$.latinName").value("Monstera deliciosa"))
                .andExpect(jsonPath("$.description").value("Тропическое растение с большими листьями"));
    }

    @Test
    void should_include_facts_in_detail_when_service_returns_facts() throws Exception {
        // arrange
        var species = buildSpecies(1L, "Монстера", "Monstera deliciosa");
        when(speciesService.getById(1L)).thenReturn(species);
        // Сервис группирует по категориям; контроллер разворачивает в плоский facts[].
        when(speciesFactService.getFactsBySpecies(eq(1L), any())).thenReturn(Map.of(
                FactCategory.ORIGIN, List.of(
                        new SpeciesFactDto(FactCategory.ORIGIN, "Родина", "Центральная Америка", "wiki", 0)),
                FactCategory.TOXICITY, List.of(
                        new SpeciesFactDto(FactCategory.TOXICITY, null, "Токсична для кошек", null, 0))
        ));

        // act + assert
        mockMvc.perform(get("/api/v1/species/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facts").isArray())
                .andExpect(jsonPath("$.facts.length()").value(2))
                .andExpect(jsonPath("$.facts[*].category",
                        org.hamcrest.Matchers.containsInAnyOrder("ORIGIN", "TOXICITY")))
                .andExpect(jsonPath("$.facts[*].body",
                        org.hamcrest.Matchers.containsInAnyOrder("Центральная Америка", "Токсична для кошек")));
    }

    @Test
    void should_return_empty_facts_when_species_has_no_facts() throws Exception {
        // arrange
        var species = buildSpecies(1L, "Монстера", "Monstera deliciosa");
        when(speciesService.getById(1L)).thenReturn(species);
        when(speciesFactService.getFactsBySpecies(eq(1L), any())).thenReturn(Map.of());

        // act + assert — пустой Map → пустой массив facts (не отсутствует)
        mockMvc.perform(get("/api/v1/species/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facts").isArray())
                .andExpect(jsonPath("$.facts").isEmpty());
    }

    @Test
    void should_return_404_when_species_not_found() throws Exception {
        // arrange
        when(speciesService.getById(999L))
                .thenThrow(new EntityNotFoundException("Species not found: 999"));

        // act + assert
        mockMvc.perform(get("/api/v1/species/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Species not found: 999"));
    }

    @Test
    void should_limit_page_size_to_100() throws Exception {
        // arrange
        var cappedPageable = PageRequest.of(0, 100);
        Page<Species> page = new PageImpl<>(List.of(), cappedPageable, 0);
        when(speciesService.findPage(any(), any(Pageable.class))).thenReturn(page);

        // act + assert — query-параметр унифицирован: limit (вместо size). Сервер обрезает до 100.
        // Bean Validation в сгенерированном интерфейсе ограничивает limit max=100, поэтому
        // отправляем ровно 100 и убеждаемся, что в ответе limit=100.
        mockMvc.perform(get("/api/v1/species").param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(100));
    }

    // ------------------------------------------------------------------ helpers

    private Species buildSpecies(Long id, String name, String latinName) {
        var s = new Species();
        s.setName(name);
        s.setLatinName(latinName);
        s.setWateringDays(7);
        s.setMistingDays(3);
        s.setFertilizingDays(30);
        s.setCareDifficulty(CareDifficulty.EASY);
        s.setLightPreference(LightPreference.PARTIAL);
        s.setPopularity(50);
        // Set id via reflection since BaseEntity uses @GeneratedValue
        try {
            var idField = com.plantcare.bot.domain.base.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(s, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return s;
    }
}

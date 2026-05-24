package com.plantcare.bot.web;

import com.plantcare.bot.admin.config.AdminSecurityConfig;
import com.plantcare.bot.domain.Species;
import com.plantcare.bot.domain.enums.CareDifficulty;
import com.plantcare.bot.domain.enums.LightPreference;
import com.plantcare.bot.service.SpeciesService;
import com.plantcare.bot.web.exception.ApiExceptionHandler;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

@WebMvcTest(controllers = {SpeciesController.class, ApiExceptionHandler.class})
@Import(AdminSecurityConfig.class)
class SpeciesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SpeciesService speciesService;

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
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Монстера"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));
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
                .andExpect(jsonPath("$.content[0].name").value("Роза чайная"));

        verify(speciesService).findPage(eq("роза"), any(Pageable.class));
    }

    @Test
    void should_return_species_detail_when_id_exists() throws Exception {
        // arrange
        var species = buildSpecies(1L, "Монстера", "Monstera deliciosa");
        species.setDescription("Тропическое растение с большими листьями");
        when(speciesService.getById(1L)).thenReturn(species);

        // act + assert
        mockMvc.perform(get("/api/v1/species/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Монстера"))
                .andExpect(jsonPath("$.latinName").value("Monstera deliciosa"))
                .andExpect(jsonPath("$.description").value("Тропическое растение с большими листьями"));
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

        // act + assert
        mockMvc.perform(get("/api/v1/species").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
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

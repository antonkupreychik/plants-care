package com.plantcare.api.v1;

import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.PlantTemplate;
import com.plantcare.core.domain.PlantTemplateCareRule;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.service.PlantTemplateService;
import com.plantcare.core.service.UserService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} for {@link PlantTemplateController} (issue #254). The controller was
 * completely uncovered — only a dead {@code PlantTemplateControllerIT.java} existed (maven
 * failsafe is not configured, so it never runs). Mirrors the {@code PlantControllerTest}
 * pattern: filters disabled + {@link ApiExceptionHandler} imported so
 * {@link EntityNotFoundException} maps to 404 instead of blowing up the test.
 */
@WebMvcTest(PlantTemplateController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class PlantTemplateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlantTemplateService plantTemplateService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    private User user;

    @BeforeEach
    void stubCurrentUser() {
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        user = User.builder().telegramChatId(100L).username("alice").build();
        ReflectionTestUtils.setField(user, "id", 1L);
        when(userService.getByIdOrThrow(1L)).thenReturn(user);
    }

    private PlantTemplate templateWithId(Long id, String name, LocalDateTime createdAt) {
        PlantTemplate template = PlantTemplate.builder().userId(1L).name(name).build();
        ReflectionTestUtils.setField(template, "id", id);
        if (createdAt != null) {
            ReflectionTestUtils.setField(template, "createdAt", createdAt);
        }
        PlantTemplateCareRule rule = PlantTemplateCareRule.builder()
                .template(template)
                .careType(TaskType.WATERING)
                .intervalDays(7)
                .build();
        template.getCareRules().add(rule);
        return template;
    }

    // ============================================================ list

    @Test
    void should_return_templates_with_care_rules_when_listing() throws Exception {
        // arrange
        LocalDateTime createdAt = LocalDateTime.of(2024, 1, 15, 10, 30);
        PlantTemplate template = templateWithId(5L, "Монстера 7д", createdAt);
        when(plantTemplateService.getUserTemplates(1L)).thenReturn(List.of(template));

        // act + assert
        mockMvc.perform(get("/api/v1/plant-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(5))
                .andExpect(jsonPath("$[0].name").value("Монстера 7д"))
                .andExpect(jsonPath("$[0].careRules[0].careType").value("WATERING"))
                .andExpect(jsonPath("$[0].careRules[0].intervalDays").value(7))
                .andExpect(jsonPath("$[0].createdAt").exists());
    }

    @Test
    void should_return_empty_array_when_no_templates() throws Exception {
        // arrange
        when(plantTemplateService.getUserTemplates(1L)).thenReturn(List.of());

        // act + assert
        mockMvc.perform(get("/api/v1/plant-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void should_return_null_created_at_when_template_has_no_timestamp() throws Exception {
        // arrange — createdAt left unset (BaseEntity default, no @CreationTimestamp applied outside JPA)
        PlantTemplate template = templateWithId(6L, "Без даты", null);
        when(plantTemplateService.getUserTemplates(1L)).thenReturn(List.of(template));

        // act + assert
        mockMvc.perform(get("/api/v1/plant-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].createdAt").doesNotExist());
    }

    // ============================================================ create

    @Test
    void should_call_saveFromPlant_when_fromPlantId_is_present() throws Exception {
        // arrange
        PlantTemplate saved = templateWithId(7L, "Из растения", LocalDateTime.now());
        when(plantTemplateService.saveFromPlant(eq(user), eq(42L), eq("Из растения"))).thenReturn(saved);

        // act + assert
        mockMvc.perform(post("/api/v1/plant-templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Из растения","fromPlantId":42}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7));

        verify(plantTemplateService).saveFromPlant(eq(user), eq(42L), eq("Из растения"));
    }

    @Test
    void should_call_createEmpty_when_fromPlantId_is_absent() throws Exception {
        // arrange
        PlantTemplate saved = templateWithId(8L, "Пустой", LocalDateTime.now());
        when(plantTemplateService.createEmpty(eq(user), eq("Пустой"))).thenReturn(saved);

        // act + assert
        mockMvc.perform(post("/api/v1/plant-templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Пустой"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(8));

        verify(plantTemplateService).createEmpty(eq(user), eq("Пустой"));
    }

    // ============================================================ delete

    @Test
    void should_delete_template_when_found() throws Exception {
        // arrange
        PlantTemplate template = templateWithId(9L, "Удаляемый", LocalDateTime.now());
        when(plantTemplateService.getTemplate(1L, 9L)).thenReturn(Optional.of(template));

        // act + assert
        mockMvc.perform(delete("/api/v1/plant-templates/9"))
                .andExpect(status().isNoContent());

        verify(plantTemplateService).deleteTemplate(user, 9L);
    }

    @Test
    void should_return_404_when_deleting_missing_template() throws Exception {
        // arrange
        when(plantTemplateService.getTemplate(1L, 999L)).thenReturn(Optional.empty());

        // act + assert
        mockMvc.perform(delete("/api/v1/plant-templates/999"))
                .andExpect(status().isNotFound());
    }

    // ============================================================ instantiate

    @Test
    void should_instantiate_plant_with_location_and_species_when_present() throws Exception {
        // arrange
        PlantTemplate template = templateWithId(10L, "Шаблон", LocalDateTime.now());
        when(plantTemplateService.getTemplate(1L, 10L)).thenReturn(Optional.of(template));

        Location location = Location.builder().name("Гостиная").build();
        ReflectionTestUtils.setField(location, "id", 3L);
        Species species = new Species();
        species.setName("Монстера");
        ReflectionTestUtils.setField(species, "id", 4L);

        Plant plant = Plant.builder().user(user).name("Монстера 2").location(location).species(species).build();
        ReflectionTestUtils.setField(plant, "id", 11L);
        when(plantTemplateService.createPlantFromTemplate(eq(user), eq(10L), eq("Монстера 2")))
                .thenReturn(plant);

        // act + assert
        mockMvc.perform(post("/api/v1/plant-templates/10/instantiate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Монстера 2"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.locationId").value(3))
                .andExpect(jsonPath("$.locationName").value("Гостиная"))
                .andExpect(jsonPath("$.speciesId").value(4))
                .andExpect(jsonPath("$.speciesName").value("Монстера"));
    }

    @Test
    void should_instantiate_plant_without_location_or_species_when_absent() throws Exception {
        // arrange
        PlantTemplate template = templateWithId(12L, "Шаблон2", LocalDateTime.now());
        when(plantTemplateService.getTemplate(1L, 12L)).thenReturn(Optional.of(template));

        Plant plant = Plant.builder().user(user).name("Без места").build();
        ReflectionTestUtils.setField(plant, "id", 13L);
        when(plantTemplateService.createPlantFromTemplate(eq(user), eq(12L), eq("Без места")))
                .thenReturn(plant);

        // act + assert
        mockMvc.perform(post("/api/v1/plant-templates/12/instantiate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Без места"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(13))
                .andExpect(jsonPath("$.locationId").doesNotExist())
                .andExpect(jsonPath("$.speciesId").doesNotExist());
    }

    @Test
    void should_return_404_when_instantiating_missing_template() throws Exception {
        // arrange
        when(plantTemplateService.getTemplate(1L, 999L)).thenReturn(Optional.empty());

        // act + assert
        mockMvc.perform(post("/api/v1/plant-templates/999/instantiate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Не важно"}
                                """))
                .andExpect(status().isNotFound());

        verify(plantTemplateService, org.mockito.Mockito.never())
                .createPlantFromTemplate(any(), anyLong(), any());
    }
}

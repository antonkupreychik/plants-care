package com.plantcare.bot.admin.controller;

import com.plantcare.bot.admin.exception.SpeciesFactNotFoundException;
import com.plantcare.bot.admin.ratelimit.LoginRateLimiter;
import com.plantcare.bot.admin.service.AdminSpeciesFactService;
import com.plantcare.bot.domain.Species;
import com.plantcare.bot.domain.SpeciesFact;
import com.plantcare.bot.domain.enums.FactCategory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Web-слайс тесты CRUD фактов вида в админке (issue #131).
 *
 * <p>Мокается только сервис ({@link AdminSpeciesFactService}) и {@link LoginRateLimiter}
 * (требуется конфигом безопасности). Контроллер всегда возвращает один HTMX-фрагмент
 * {@code admin/species/facts/section :: factsSection}; разница между успехом и ошибкой
 * валидации — в том, дёрнут ли сервис и есть ли field-errors в модели.
 */
@WebMvcTest(AdminSpeciesFactController.class)
@Import(AdminControllerAdvice.class)
@WithMockUser(roles = "ADMIN")
class AdminSpeciesFactControllerTest {

    private static final String FACTS_FRAGMENT = "admin/species/facts/section :: factsSection";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LoginRateLimiter loginRateLimiter;

    @MockitoBean
    private AdminSpeciesFactService adminSpeciesFactService;

    // ============================================================ create

    @Test
    void should_call_service_and_render_section_when_create_with_valid_body() throws Exception {
        // arrange
        when(adminSpeciesFactService.getFactsGrouped(7L)).thenReturn(Map.of());

        // act
        mockMvc.perform(post("/admin/species/7/facts").with(csrf())
                        .param("category", "CARE")
                        .param("title", "Полив")
                        .param("body", "Поливать раз в неделю")
                        .param("source", "Книга про растения")
                        .param("displayOrder", "0"))
                .andExpect(status().isOk())
                .andExpect(view().name(FACTS_FRAGMENT))
                .andExpect(model().hasNoErrors());

        // assert — сервис получил именно те значения, что пришли в форме
        verify(adminSpeciesFactService).createFact(
                eq(7L),
                argThat(dto ->
                        dto.category() == FactCategory.CARE
                                && "Полив".equals(dto.title())
                                && "Поливать раз в неделю".equals(dto.body())
                                && "Книга про растения".equals(dto.source())
                                && dto.displayOrder() == 0),
                eq("user"));
    }

    @Test
    void should_not_call_service_when_create_with_blank_body() throws Exception {
        // act
        mockMvc.perform(post("/admin/species/7/facts").with(csrf())
                        .param("category", "CARE")
                        .param("body", "")
                        .param("displayOrder", "0"))
                .andExpect(status().isOk())
                .andExpect(view().name(FACTS_FRAGMENT))
                .andExpect(model().attributeHasFieldErrors("factForm", "body"));

        // assert — валидация отсекла запрос, факт не создан
        verify(adminSpeciesFactService, never()).createFact(any(), any(), any());
    }

    @Test
    void should_not_call_service_when_create_without_category() throws Exception {
        // act — @NotNull category: пустое значение enum не биндится
        mockMvc.perform(post("/admin/species/7/facts").with(csrf())
                        .param("category", "")
                        .param("body", "Текст есть, категории нет")
                        .param("displayOrder", "0"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("factForm", "category"));

        // assert
        verify(adminSpeciesFactService, never()).createFact(any(), any(), any());
    }

    @Test
    void should_not_call_service_when_create_with_too_long_title() throws Exception {
        // arrange — title длиной 161 при @Size(max = 160)
        String tooLong = "т".repeat(161);

        // act
        mockMvc.perform(post("/admin/species/7/facts").with(csrf())
                        .param("category", "CARE")
                        .param("title", tooLong)
                        .param("body", "Валидное тело факта")
                        .param("displayOrder", "0"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("factForm", "title"));

        // assert
        verify(adminSpeciesFactService, never()).createFact(any(), any(), any());
    }

    @Test
    void should_not_call_service_when_create_with_negative_display_order() throws Exception {
        // act — @PositiveOrZero displayOrder
        mockMvc.perform(post("/admin/species/7/facts").with(csrf())
                        .param("category", "CARE")
                        .param("body", "Валидное тело факта")
                        .param("displayOrder", "-1"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("factForm", "displayOrder"));

        // assert
        verify(adminSpeciesFactService, never()).createFact(any(), any(), any());
    }

    // ============================================================ update

    @Test
    void should_call_update_when_update_with_valid_body() throws Exception {
        // arrange
        when(adminSpeciesFactService.getFactsGrouped(7L)).thenReturn(Map.of());

        // act
        mockMvc.perform(post("/admin/species/7/facts/42").with(csrf())
                        .param("category", "TOXICITY")
                        .param("body", "Токсичен для кошек")
                        .param("displayOrder", "1"))
                .andExpect(status().isOk())
                .andExpect(view().name(FACTS_FRAGMENT))
                .andExpect(model().hasNoErrors());

        // assert
        verify(adminSpeciesFactService).updateFact(
                eq(7L),
                eq(42L),
                argThat(dto ->
                        dto.category() == FactCategory.TOXICITY
                                && "Токсичен для кошек".equals(dto.body())
                                && dto.displayOrder() == 1),
                eq("user"));
    }

    @Test
    void should_not_call_update_when_update_with_blank_body() throws Exception {
        // arrange
        when(adminSpeciesFactService.getFactsGrouped(7L)).thenReturn(Map.of());

        // act
        mockMvc.perform(post("/admin/species/7/facts/42").with(csrf())
                        .param("category", "TOXICITY")
                        .param("body", "")
                        .param("displayOrder", "1"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("factForm", "body"))
                // при ошибке форма остаётся в режиме правки выбранного факта
                .andExpect(model().attribute("editingFactId", 42L));

        // assert
        verify(adminSpeciesFactService, never()).updateFact(any(), any(), any(), any());
    }

    // ============================================================ delete

    @Test
    void should_call_delete_and_render_section_when_delete_fact() throws Exception {
        // arrange
        when(adminSpeciesFactService.getFactsGrouped(7L)).thenReturn(Map.of());

        // act
        mockMvc.perform(delete("/admin/species/7/facts/42").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(FACTS_FRAGMENT));

        // assert
        verify(adminSpeciesFactService).deleteFact(eq(7L), eq(42L), eq("user"));
    }

    // ============================================================ edit prefill

    @Test
    void should_prefill_form_when_edit_form_requested() throws Exception {
        // arrange
        Species species = new Species();
        species.setName("Монстера");
        SpeciesFact fact = new SpeciesFact();
        fact.setSpecies(species);
        fact.setCategory(FactCategory.ORIGIN);
        fact.setBody("Родом из Центральной Америки");
        fact.setDisplayOrder(0);
        when(adminSpeciesFactService.getOwnedFact(7L, 42L)).thenReturn(fact);
        when(adminSpeciesFactService.getFactsGrouped(7L))
                .thenReturn(Map.of(FactCategory.ORIGIN, List.of(fact)));

        // act
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/admin/species/7/facts/42/edit"))
                .andExpect(status().isOk())
                .andExpect(view().name(FACTS_FRAGMENT))
                .andExpect(model().attribute("editingFactId", 42L))
                .andExpect(model().attributeExists("factForm"));

        // assert
        verify(adminSpeciesFactService).getOwnedFact(7L, 42L);
    }

    // ============================================================ not-found → 404

    @Test
    void should_return_404_when_update_targets_fact_not_owned_by_species() throws Exception {
        // arrange — сервис не нашёл факт у этого вида (защита от подмены factId)
        doThrow(new SpeciesFactNotFoundException(42L, 7L))
                .when(adminSpeciesFactService).updateFact(eq(7L), eq(42L), any(), eq("user"));

        // act
        mockMvc.perform(post("/admin/species/7/facts/42").with(csrf())
                        .param("category", "TOXICITY")
                        .param("body", "Токсичен для кошек")
                        .param("displayOrder", "1"))
                .andExpect(status().isNotFound());

        // assert — пробросилось в сервис, секция не перерисовывалась
        verify(adminSpeciesFactService).updateFact(eq(7L), eq(42L), any(), eq("user"));
        verify(adminSpeciesFactService, never()).getFactsGrouped(any());
    }

    @Test
    void should_return_404_when_delete_targets_fact_not_owned_by_species() throws Exception {
        // arrange
        doThrow(new SpeciesFactNotFoundException(42L, 7L))
                .when(adminSpeciesFactService).deleteFact(7L, 42L, "user");

        // act
        mockMvc.perform(delete("/admin/species/7/facts/42").with(csrf()))
                .andExpect(status().isNotFound());

        // assert
        verify(adminSpeciesFactService).deleteFact(7L, 42L, "user");
        verify(adminSpeciesFactService, never()).getFactsGrouped(any());
    }

    @Test
    void should_return_404_when_edit_form_targets_fact_not_owned_by_species() throws Exception {
        // arrange
        when(adminSpeciesFactService.getOwnedFact(7L, 42L))
                .thenThrow(new SpeciesFactNotFoundException(42L, 7L));

        // act
        mockMvc.perform(get("/admin/species/7/facts/42/edit"))
                .andExpect(status().isNotFound());

        // assert
        verify(adminSpeciesFactService).getOwnedFact(7L, 42L);
        verify(adminSpeciesFactService, never()).getFactsGrouped(any());
    }
}

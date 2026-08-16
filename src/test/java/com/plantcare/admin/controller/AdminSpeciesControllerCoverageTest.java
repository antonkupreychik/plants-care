package com.plantcare.admin.controller;

import com.plantcare.admin.dto.SpeciesFormDto;
import com.plantcare.admin.dto.SpeciesListItem;
import com.plantcare.admin.exception.DuplicateSpeciesNameException;
import com.plantcare.admin.service.AdminSpeciesFactService;
import com.plantcare.admin.service.AdminSpeciesService;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.enums.CareDifficulty;
import com.plantcare.core.domain.enums.LightPreference;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Web-слайс тесты {@link AdminSpeciesController} (issue: раскрытие JaCoCo-покрытия).
 *
 * <p>Контроллер был практически не покрыт — единственный существовавший тест
 * (AdminSpeciesControllerTest) был закомментирован из-за миграции на Spring Boot 4.1.
 * Этот класс переносит тот же сценарий на актуальные API (@MockitoBean,
 * org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest) и добавляет
 * недостающие ветки: editForm (found/404), update (валидный/ошибки/дубликат),
 * patchField с ошибкой сервиса.
 */
@WebMvcTest(AdminSpeciesController.class)
@Import(AdminControllerAdvice.class)
// username задан явно: контроллер прокидывает auth.getName() в сервис как «кто изменил»,
// и тесты проверяют именно это значение. По умолчанию @WithMockUser дал бы "user".
@WithMockUser(username = "admin", roles = "ADMIN")
class AdminSpeciesControllerCoverageTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminSpeciesService adminSpeciesService;

    @MockitoBean
    private AdminSpeciesFactService adminSpeciesFactService;

    // ============================================================ list

    @Test
    void should_return_ok_and_full_view_when_listing_without_hx_header() throws Exception {
        // arrange
        Page<SpeciesListItem> emptyPage = new PageImpl<>(List.of());
        when(adminSpeciesService.findAllWithLinkedCounts(any(), anyInt(), any(), any()))
                .thenReturn(emptyPage);

        // act + assert
        mockMvc.perform(get("/admin/species"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/species/list"))
                .andExpect(model().attribute("dir", "desc"))
                .andExpect(model().attribute("reverseDir", "asc"));
    }

    @Test
    void should_return_table_fragment_when_hx_request_header_present() throws Exception {
        // arrange
        Page<SpeciesListItem> emptyPage = new PageImpl<>(List.of());
        when(adminSpeciesService.findAllWithLinkedCounts(any(), anyInt(), any(), any()))
                .thenReturn(emptyPage);

        // act + assert
        mockMvc.perform(get("/admin/species").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/species/list :: tableWrapper"));
    }

    @Test
    void should_flip_reverse_dir_to_desc_when_dir_param_is_asc() throws Exception {
        // arrange
        Page<SpeciesListItem> emptyPage = new PageImpl<>(List.of());
        when(adminSpeciesService.findAllWithLinkedCounts(any(), anyInt(), any(), any()))
                .thenReturn(emptyPage);

        // act + assert
        mockMvc.perform(get("/admin/species").param("dir", "asc"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("reverseDir", "desc"));
    }

    @Test
    void should_pass_query_param_to_service_when_searching() throws Exception {
        // arrange
        Page<SpeciesListItem> emptyPage = new PageImpl<>(List.of());
        when(adminSpeciesService.findAllWithLinkedCounts(eq("monstera"), anyInt(), any(), any()))
                .thenReturn(emptyPage);

        // act
        mockMvc.perform(get("/admin/species").param("q", "monstera"))
                .andExpect(status().isOk());

        // assert
        verify(adminSpeciesService).findAllWithLinkedCounts(eq("monstera"), eq(0), any(), any());
    }

    // ============================================================ create

    @Test
    void should_return_form_view_when_requesting_new_form() throws Exception {
        // act + assert
        mockMvc.perform(get("/admin/species/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/species/form"))
                .andExpect(model().attributeExists("speciesForm"))
                .andExpect(model().attribute("speciesId", (Object) null));
    }

    @Test
    void should_redirect_to_list_when_create_with_valid_form() throws Exception {
        // arrange
        when(adminSpeciesService.create(any(), eq("admin"))).thenReturn(speciesWithId(1L, "Монстера"));

        // act + assert
        mockMvc.perform(post("/admin/species").with(csrf())
                        .param("name", "Монстера")
                        .param("popularity", "50"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/species"));

        verify(adminSpeciesService).create(any(), eq("admin"));
    }

    @Test
    void should_return_form_with_field_error_when_create_name_is_blank() throws Exception {
        // act + assert
        mockMvc.perform(post("/admin/species").with(csrf())
                        .param("name", "")
                        .param("popularity", "50"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/species/form"))
                .andExpect(model().attributeHasFieldErrors("speciesForm", "name"));
    }

    @Test
    void should_return_form_with_duplicate_error_when_create_name_already_exists() throws Exception {
        // arrange
        when(adminSpeciesService.create(any(), any()))
                .thenThrow(new DuplicateSpeciesNameException("Фикус"));

        // act + assert
        mockMvc.perform(post("/admin/species").with(csrf())
                        .param("name", "Фикус")
                        .param("popularity", "50"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/species/form"))
                .andExpect(model().attributeHasFieldErrors("speciesForm", "name"))
                .andExpect(content().string(containsString("Вид с таким именем уже существует")));
    }

    @Test
    void should_return_form_with_field_error_when_create_popularity_out_of_range() throws Exception {
        // act + assert
        mockMvc.perform(post("/admin/species").with(csrf())
                        .param("name", "Тест")
                        .param("popularity", "150"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/species/form"))
                .andExpect(model().attributeHasFieldErrors("speciesForm", "popularity"));
    }

    @Test
    void should_return_form_with_field_error_when_create_watering_days_too_large() throws Exception {
        // act + assert
        mockMvc.perform(post("/admin/species").with(csrf())
                        .param("name", "Тест")
                        .param("popularity", "50")
                        .param("wateringDays", "9999"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("speciesForm", "wateringDays"));
    }

    // ============================================================ edit form

    @Test
    void should_return_form_view_with_species_data_when_edit_form_found() throws Exception {
        // arrange
        Species species = speciesWithId(1L, "Хойя");
        when(adminSpeciesService.findById(1L)).thenReturn(species);
        when(adminSpeciesFactService.getFactsGrouped(1L)).thenReturn(Map.of());

        // act + assert
        mockMvc.perform(get("/admin/species/1/edit"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/species/form"))
                .andExpect(model().attribute("speciesId", 1L))
                .andExpect(model().attribute("pageTitle", "Редактирование: Хойя"));
    }

    @Test
    void should_return_404_when_edit_form_species_not_found() throws Exception {
        // arrange
        when(adminSpeciesService.findById(999L))
                .thenThrow(new EntityNotFoundException("Вид не найден: 999"));

        // act + assert
        mockMvc.perform(get("/admin/species/999/edit"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Вид не найден: 999")));
    }

    // ============================================================ update

    @Test
    void should_redirect_to_list_when_update_with_valid_form() throws Exception {
        // arrange
        when(adminSpeciesService.update(eq(1L), any(), eq("admin")))
                .thenReturn(speciesWithId(1L, "Обновлённая"));

        // act + assert
        mockMvc.perform(post("/admin/species/1").with(csrf())
                        .param("name", "Обновлённая")
                        .param("popularity", "50"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/species"));

        verify(adminSpeciesService).update(eq(1L), any(), eq("admin"));
    }

    @Test
    void should_return_form_with_field_error_when_update_name_is_blank() throws Exception {
        // arrange: страница редактирования подключает секцию фактов — она нужна и на
        // ветке ошибки валидации, иначе шаблон разыменует null и вернёт 500.
        when(adminSpeciesFactService.getFactsGrouped(1L)).thenReturn(Map.of());

        // act + assert
        mockMvc.perform(post("/admin/species/1").with(csrf())
                        .param("name", "")
                        .param("popularity", "50"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/species/form"))
                .andExpect(model().attributeHasFieldErrors("speciesForm", "name"));
    }

    @Test
    void should_return_form_with_duplicate_error_when_update_name_already_exists() throws Exception {
        // arrange
        when(adminSpeciesFactService.getFactsGrouped(1L)).thenReturn(Map.of());
        when(adminSpeciesService.update(eq(1L), any(), any()))
                .thenThrow(new DuplicateSpeciesNameException("Фикус"));

        // act + assert
        mockMvc.perform(post("/admin/species/1").with(csrf())
                        .param("name", "Фикус")
                        .param("popularity", "50"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/species/form"))
                .andExpect(model().attributeHasFieldErrors("speciesForm", "name"))
                .andExpect(content().string(containsString("Вид с таким именем уже существует")));
    }

    // ============================================================ delete

    @Test
    void should_redirect_to_list_when_delete_called() throws Exception {
        // arrange
        doNothing().when(adminSpeciesService).delete(eq(42L), any());

        // act + assert
        mockMvc.perform(delete("/admin/species/42").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/species"));

        verify(adminSpeciesService).delete(eq(42L), any());
    }

    // ============================================================ HTMX inline edit

    @Test
    void should_return_editable_fragment_when_cell_mode_is_edit() throws Exception {
        // arrange
        when(adminSpeciesService.findById(1L)).thenReturn(speciesWithId(1L, "Тест"));

        // act + assert
        mockMvc.perform(get("/admin/species/1/cell").param("field", "name"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/species/edit-cell :: editableCell"));
    }

    @Test
    void should_return_readonly_fragment_when_cell_mode_is_view() throws Exception {
        // arrange
        when(adminSpeciesService.findById(1L)).thenReturn(speciesWithId(1L, "Тест"));

        // act + assert
        mockMvc.perform(get("/admin/species/1/cell")
                        .param("field", "name")
                        .param("mode", "view"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/species/edit-cell :: readonlyCell"));
    }

    @Test
    void should_return_readonly_cell_when_patch_field_succeeds() throws Exception {
        // arrange
        when(adminSpeciesService.patchField(eq(1L), eq("name"), eq("Обновлённое имя"), any()))
                .thenReturn(speciesWithId(1L, "Обновлённое имя"));

        // act + assert
        mockMvc.perform(patch("/admin/species/1").with(csrf())
                        .param("field", "name")
                        .param("value", "Обновлённое имя"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/species/edit-cell :: readonlyCell"));
    }

    @Test
    void should_return_400_when_patch_field_service_rejects_value() throws Exception {
        // arrange
        when(adminSpeciesService.patchField(eq(1L), eq("popularity"), eq("999"), any()))
                .thenThrow(new IllegalArgumentException("Популярность от 0 до 100"));

        // act + assert
        mockMvc.perform(patch("/admin/species/1").with(csrf())
                        .param("field", "popularity")
                        .param("value", "999"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Популярность от 0 до 100")));
    }

    // ============================================================ helpers

    private Species speciesWithId(Long id, String name) {
        Species s = new Species();
        s.setName(name);
        s.setPopularity(50);
        s.setLightPreference(LightPreference.BRIGHT);
        s.setCareDifficulty(CareDifficulty.EASY);
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }
}

/*
package com.plantcare.admin.controller;

import com.plantcare.admin.dto.SpeciesListItem;
import com.plantcare.admin.exception.DuplicateSpeciesNameException;
import com.plantcare.admin.ratelimit.LoginRateLimiter;
import com.plantcare.admin.service.AdminSpeciesService;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.enums.CareDifficulty;
import com.plantcare.core.domain.enums.LightPreference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminSpeciesController.class)
@WithMockUser(roles = "ADMIN")
class AdminSpeciesControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private LoginRateLimiter loginRateLimiter;

    @MockitoBean
    private AdminSpeciesService adminSpeciesService;

    // ============================================================ list

    @Test
    void list_returnsOk() throws Exception {
        Page<SpeciesListItem> emptyPage = new PageImpl<>(List.of());
        when(adminSpeciesService.findAllWithLinkedCounts(any(), anyInt(), any(), any()))
                .thenReturn(emptyPage);

        mockMvc.perform(get("/admin/species"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/species/list"));
    }

    @Test
    void list_withHxRequestHeader_returnsFragmentOnly() throws Exception {
        Page<SpeciesListItem> emptyPage = new PageImpl<>(List.of());
        when(adminSpeciesService.findAllWithLinkedCounts(any(), anyInt(), any(), any()))
                .thenReturn(emptyPage);

        mockMvc.perform(get("/admin/species").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/species/list :: tableWrapper"));
    }

    @Test
    void list_withQuery_passesQueryToService() throws Exception {
        Page<SpeciesListItem> emptyPage = new PageImpl<>(List.of());
        when(adminSpeciesService.findAllWithLinkedCounts(eq("monstera"), anyInt(), any(), any()))
                .thenReturn(emptyPage);

        mockMvc.perform(get("/admin/species").param("q", "monstera"))
                .andExpect(status().isOk());

        verify(adminSpeciesService).findAllWithLinkedCounts(eq("monstera"), eq(0), any(), any());
    }

    // ============================================================ create

    @Test
    void newForm_returnsFormView() throws Exception {
        mockMvc.perform(get("/admin/species/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/species/form"))
                .andExpect(model().attributeExists("speciesForm"));
    }

    @Test
    void create_validForm_redirectsToList() throws Exception {
        when(adminSpeciesService.create(any(), any())).thenReturn(speciesWithId(1L, "Монстера"));

        mockMvc.perform(post("/admin/species").with(csrf())
                        .param("name", "Монстера")
                        .param("popularity", "50"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/species"));
    }

    @Test
    void create_blankName_returnsFormWithErrors() throws Exception {
        mockMvc.perform(post("/admin/species").with(csrf())
                        .param("name", "")
                        .param("popularity", "50"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/species/form"))
                .andExpect(model().attributeHasFieldErrors("speciesForm", "name"));
    }

    @Test
    void create_duplicateName_returnsFormWithDuplicateError() throws Exception {
        when(adminSpeciesService.create(any(), any()))
                .thenThrow(new DuplicateSpeciesNameException("Фикус"));

        mockMvc.perform(post("/admin/species").with(csrf())
                        .param("name", "Фикус")
                        .param("popularity", "50"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/species/form"))
                .andExpect(model().attributeHasFieldErrors("speciesForm", "name"))
                .andExpect(content().string(containsString("Вид с таким именем уже существует")));
    }

    @Test
    void create_popularityOutOfRange_returnsFormWithErrors() throws Exception {
        mockMvc.perform(post("/admin/species").with(csrf())
                        .param("name", "Тест")
                        .param("popularity", "150"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/species/form"))
                .andExpect(model().attributeHasFieldErrors("speciesForm", "popularity"));
    }

    @Test
    void create_wateringDaysTooLarge_returnsFormWithErrors() throws Exception {
        mockMvc.perform(post("/admin/species").with(csrf())
                        .param("name", "Тест")
                        .param("popularity", "50")
                        .param("wateringDays", "9999"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("speciesForm", "wateringDays"));
    }

    // ============================================================ delete

    @Test
    void delete_callsServiceAndRedirects() throws Exception {
        doNothing().when(adminSpeciesService).delete(eq(42L), any());

        mockMvc.perform(delete("/admin/species/42").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/species"));

        verify(adminSpeciesService).delete(eq(42L), any());
    }

    // ============================================================ HTMX inline edit

    @Test
    void getCell_editMode_returnsEditableFragment() throws Exception {
        when(adminSpeciesService.findById(1L)).thenReturn(speciesWithId(1L, "Тест"));

        mockMvc.perform(get("/admin/species/1/cell")
                        .param("field", "name"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/species/edit-cell :: editableCell"));
    }

    @Test
    void getCell_viewMode_returnsReadonlyFragment() throws Exception {
        when(adminSpeciesService.findById(1L)).thenReturn(speciesWithId(1L, "Тест"));

        mockMvc.perform(get("/admin/species/1/cell")
                        .param("field", "name")
                        .param("mode", "view"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/species/edit-cell :: readonlyCell"));
    }

    @Test
    void patchField_returnsReadonlyCell() throws Exception {
        when(adminSpeciesService.patchField(eq(1L), eq("name"), eq("Обновлённое имя"), any()))
                .thenReturn(speciesWithId(1L, "Обновлённое имя"));

        mockMvc.perform(patch("/admin/species/1").with(csrf())
                        .param("field", "name")
                        .param("value", "Обновлённое имя"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/species/edit-cell :: readonlyCell"));
    }

    // ============================================================ helpers

    private Species speciesWithId(Long id, String name) {
        Species s = new Species();
        s.setName(name);
        s.setPopularity(50);
        s.setLightPreference(LightPreference.BRIGHT);
        s.setCareDifficulty(CareDifficulty.EASY);
        return s;
    }
}
*/

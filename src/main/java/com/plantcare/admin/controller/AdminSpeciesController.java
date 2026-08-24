package com.plantcare.admin.controller;

import com.plantcare.admin.dto.SpeciesFormDto;
import com.plantcare.admin.dto.SpeciesListItem;
import com.plantcare.admin.exception.DuplicateSpeciesNameException;
import com.plantcare.admin.service.AdminSpeciesFactService;
import com.plantcare.admin.service.AdminSpeciesService;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.enums.CareDifficulty;
import com.plantcare.core.domain.enums.LightPreference;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

@Controller
@RequestMapping("/admin/species")
@RequiredArgsConstructor
public class AdminSpeciesController {

    /**
     * Русские подписи enum-значений. Хранятся в контроллере, а не в самих enum'ах,
     * чтобы не модифицировать существующие модели в com.plantcare.core.domain.enums.
     */
    private static final Map<LightPreference, String> LIGHT_LABELS = Map.of(
            LightPreference.SHADE,   "Тень",
            LightPreference.PARTIAL, "Полутень",
            LightPreference.BRIGHT,  "Яркий",
            LightPreference.DIRECT,  "Прямые лучи"
    );

    private static final Map<CareDifficulty, String> DIFFICULTY_LABELS = Map.of(
            CareDifficulty.EASY,   "Лёгкое",
            CareDifficulty.MEDIUM, "Среднее",
            CareDifficulty.HARD,   "Сложное"
    );

    private final AdminSpeciesService adminSpeciesService;
    private final AdminSpeciesFactService adminSpeciesFactService;

    // ============================================================ list + search

    @GetMapping
    public String list(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "popularity") String sort,
            @RequestParam(defaultValue = "desc") String dir,
            HttpServletRequest request,
            Model model) {

        Page<SpeciesListItem> speciesPage = adminSpeciesService.findAllWithLinkedCounts(q, page, sort, dir);

        model.addAttribute("speciesPage", speciesPage);
        model.addAttribute("q", q);
        model.addAttribute("sort", sort);
        model.addAttribute("dir", dir);
        model.addAttribute("reverseDir", "asc".equals(dir) ? "desc" : "asc");

        // HTMX-запрос (поиск, пагинация) — возвращаем только фрагмент таблицы
        if ("true".equals(request.getHeader("HX-Request"))) {
            return "admin/species/list :: tableWrapper";
        }
        return "admin/species/list";
    }

    // ============================================================ create

    @GetMapping("/new")
    public String newForm(Model model) {
        prepareFormModel(model, new SpeciesFormDto(), "/admin/species", "Новый вид", null);
        return "admin/species/form";
    }

    @PostMapping
    public String create(
            @Valid @ModelAttribute("speciesForm") SpeciesFormDto dto,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes ra,
            Authentication auth) {

        if (bindingResult.hasErrors()) {
            prepareFormModel(model, dto, "/admin/species", "Новый вид", null);
            return "admin/species/form";
        }

        try {
            adminSpeciesService.create(dto, auth.getName());
            ra.addFlashAttribute("flashSuccess", "Вид создан");
        } catch (DuplicateSpeciesNameException e) {
            bindingResult.rejectValue("name", "duplicate", e.getMessage());
            prepareFormModel(model, dto, "/admin/species", "Новый вид", null);
            return "admin/species/form";
        }

        return "redirect:/admin/species";
    }

    // ============================================================ update (full form)

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Species species = adminSpeciesService.findById(id);
        prepareFormModel(model, toDto(species),
                "/admin/species/" + id, "Редактирование: " + species.getName(), id);

        // Секция управления фактами (issue #131) — только на странице редактирования.
        AdminSpeciesFactController.populateFactsSection(
                id, adminSpeciesFactService.getFactsGrouped(id), model);

        return "admin/species/form";
    }

    @PostMapping("/{id}")
    public String update(
            @PathVariable Long id,
            @Valid @ModelAttribute("speciesForm") SpeciesFormDto dto,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes ra,
            Authentication auth) {

        if (bindingResult.hasErrors()) {
            prepareEditFormModel(model, dto, id);
            return "admin/species/form";
        }

        try {
            adminSpeciesService.update(id, dto, auth.getName());
            ra.addFlashAttribute("flashSuccess", "Вид обновлён");
        } catch (DuplicateSpeciesNameException e) {
            bindingResult.rejectValue("name", "duplicate", e.getMessage());
            prepareEditFormModel(model, dto, id);
            return "admin/species/form";
        }

        return "redirect:/admin/species";
    }

    /**
     * Модель страницы редактирования вида.
     *
     * <p>Шаблон {@code admin/species/form} на странице редактирования подключает секцию фактов
     * (issue #131), а она разыменовывает {@code factsGrouped}. Поэтому КАЖДЫЙ возврат
     * {@code admin/species/form} с непустым {@code id} обязан положить в модель и секцию фактов,
     * а не только {@link #prepareFormModel}. Раньше это делал лишь GET-обработчик формы, из-за чего
     * POST с ошибкой валидации (пустое или дублирующееся имя) падал на NPE в SpringEL и отдавал 500
     * вместо перерисованной формы с ошибкой.
     */
    private void prepareEditFormModel(Model model, SpeciesFormDto dto, Long id) {
        prepareFormModel(model, dto, "/admin/species/" + id, "Редактирование вида", id);
        AdminSpeciesFactController.populateFactsSection(
                id, adminSpeciesFactService.getFactsGrouped(id), model);
    }

    // ============================================================ delete

    @DeleteMapping("/{id}")
    public String delete(@PathVariable Long id, RedirectAttributes ra, Authentication auth) {
        adminSpeciesService.delete(id, auth.getName());
        ra.addFlashAttribute("flashSuccess", "Вид удалён");
        return "redirect:/admin/species";
    }

    // ============================================================ HTMX inline edit

    /**
     * Отдаёт <input>/<select> для редактирования одной ячейки (mode=edit)
     * или readonly-ячейку при отмене (mode=view).
     *
     * Trigger: hx-get="/admin/species/{id}/cell?field=name&mode=edit"
     */
    @GetMapping("/{id}/cell")
    public String cell(@PathVariable Long id,
                       @RequestParam String field,
                       @RequestParam(defaultValue = "edit") String mode,
                       Model model) {
        Species species = adminSpeciesService.findById(id);
        model.addAttribute("species", species);
        model.addAttribute("field", field);
        model.addAttribute("lightPreferences", LightPreference.values());
        model.addAttribute("careDifficulties", CareDifficulty.values());
        model.addAttribute("lightPreferenceLabels", LIGHT_LABELS);
        model.addAttribute("careDifficultyLabels", DIFFICULTY_LABELS);

        return "view".equals(mode)
                ? "admin/species/edit-cell :: readonlyCell"
                : "admin/species/edit-cell :: editableCell";
    }

    /**
     * Сохраняет одно поле (HTMX PATCH) и возвращает readonly-фрагмент.
     */
    @PatchMapping("/{id}")
    public String patchField(@PathVariable Long id,
                             @RequestParam String field,
                             @RequestParam(required = false) String value,
                             Model model,
                             Authentication auth) {
        Species species = adminSpeciesService.patchField(id, field, value, auth.getName());
        model.addAttribute("species", species);
        model.addAttribute("field", field);
        return "admin/species/edit-cell :: readonlyCell";
    }

    // ============================================================ helpers

    private void prepareFormModel(Model model, SpeciesFormDto dto, String formAction,
                                  String pageTitle, Long speciesId) {
        model.addAttribute("speciesForm", dto);
        model.addAttribute("lightPreferences", LightPreference.values());
        model.addAttribute("careDifficulties", CareDifficulty.values());
        model.addAttribute("lightPreferenceLabels", LIGHT_LABELS);
        model.addAttribute("careDifficultyLabels", DIFFICULTY_LABELS);
        model.addAttribute("formAction", formAction);
        model.addAttribute("pageTitle", pageTitle);
        model.addAttribute("speciesId", speciesId);
    }

    private SpeciesFormDto toDto(Species s) {
        SpeciesFormDto dto = new SpeciesFormDto();
        dto.setName(s.getName());
        dto.setLatinName(s.getLatinName());
        dto.setWateringDays(s.getWateringDays());
        dto.setMistingDays(s.getMistingDays());
        dto.setFertilizingDays(s.getFertilizingDays());
        dto.setLightPreference(s.getLightPreference());
        dto.setCareDifficulty(s.getCareDifficulty());
        dto.setDescription(s.getDescription());
        dto.setSearchTags(s.getSearchTags());
        dto.setPopularity(s.getPopularity());
        dto.setToxicToCats(s.getToxicToCats());
        dto.setToxicToDogs(s.getToxicToDogs());
        dto.setToxicToHumans(s.getToxicToHumans());
        return dto;
    }
}

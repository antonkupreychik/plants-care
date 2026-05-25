package com.plantcare.bot.admin.controller;

import com.plantcare.bot.admin.dto.SpeciesFactFormDto;
import com.plantcare.bot.admin.service.AdminSpeciesFactService;
import com.plantcare.bot.domain.SpeciesFact;
import com.plantcare.bot.domain.enums.FactCategory;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.Map;

/**
 * Админский CRUD энциклопедических фактов вида (issue #131).
 *
 * <p>Тонкий контроллер: парсит запрос → дёргает {@link AdminSpeciesFactService} →
 * возвращает HTMX-фрагмент обновлённой секции фактов
 * ({@code admin/species/facts/section :: factsSection}).
 *
 * <p>Все операции вложены в вид ({@code /admin/species/{speciesId}/facts}); сервис
 * проверяет принадлежность факта виду, защищая от подмены {@code factId}.
 */
@Controller
@RequestMapping("/admin/species/{speciesId}/facts")
@RequiredArgsConstructor
public class AdminSpeciesFactController {

    /**
     * Русские подписи категорий. Держим в контроллере, чтобы не трогать enum
     * в {@code com.plantcare.bot.domain.enums}.
     */
    static final Map<FactCategory, String> CATEGORY_LABELS = Map.of(
            FactCategory.ORIGIN,    "Происхождение",
            FactCategory.CARE,      "Уход",
            FactCategory.TOXICITY,  "Токсичность",
            FactCategory.CURIOSITY, "Интересное"
    );

    private static final String FACTS_FRAGMENT = "admin/species/facts/section :: factsSection";

    private final AdminSpeciesFactService adminSpeciesFactService;

    /**
     * Перерисовка секции с пустой формой (отмена редактирования).
     */
    @GetMapping
    public String section(@PathVariable Long speciesId, Model model) {
        return renderSection(speciesId, model);
    }

    /**
     * Секция с формой в режиме правки выбранного факта (prefill значениями).
     */
    @GetMapping("/{factId}/edit")
    public String editForm(@PathVariable Long speciesId,
                           @PathVariable Long factId,
                           Model model) {
        SpeciesFact fact = adminSpeciesFactService.getOwnedFact(speciesId, factId);
        model.addAttribute("factForm", SpeciesFactFormDto.from(fact));
        model.addAttribute("editingFactId", factId);
        return renderSection(speciesId, model);
    }

    /**
     * Создаёт факт. При ошибках валидации форма перерисовывается с сообщениями,
     * при успехе — очищается. Возвращает HTMX-фрагмент секции.
     */
    @PostMapping
    public String create(@PathVariable Long speciesId,
                         @Valid @ModelAttribute("factForm") SpeciesFactFormDto dto,
                         BindingResult bindingResult,
                         Model model,
                         Authentication auth) {
        if (!bindingResult.hasErrors()) {
            adminSpeciesFactService.createFact(speciesId, dto, auth.getName());
            // После успешного создания форму очищаем.
            model.addAttribute("factForm", emptyForm());
        }
        return renderSection(speciesId, model);
    }

    /**
     * Обновляет факт. При ошибках валидации форма остаётся в режиме правки этого
     * факта, при успехе — очищается. Возвращает HTMX-фрагмент секции.
     */
    @PostMapping("/{factId}")
    public String update(@PathVariable Long speciesId,
                         @PathVariable Long factId,
                         @Valid @ModelAttribute("factForm") SpeciesFactFormDto dto,
                         BindingResult bindingResult,
                         Model model,
                         Authentication auth) {
        if (!bindingResult.hasErrors()) {
            adminSpeciesFactService.updateFact(speciesId, factId, dto, auth.getName());
            model.addAttribute("factForm", emptyForm());
        } else {
            // Сохраняем редактируемый id, чтобы форма ниже секции осталась в режиме правки.
            model.addAttribute("editingFactId", factId);
        }
        return renderSection(speciesId, model);
    }

    /**
     * Удаляет факт и возвращает HTMX-фрагмент обновлённой секции.
     */
    @DeleteMapping("/{factId}")
    public String delete(@PathVariable Long speciesId,
                         @PathVariable Long factId,
                         Model model,
                         Authentication auth) {
        adminSpeciesFactService.deleteFact(speciesId, factId, auth.getName());
        return renderSection(speciesId, model);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Кладёт в модель данные секции фактов и возвращает имя фрагмента.
     * Используется и контроллером вида при первичном рендере страницы edit.
     */
    static String populateFactsSection(Long speciesId,
                                       Map<FactCategory, List<SpeciesFact>> grouped,
                                       Model model) {
        model.addAttribute("speciesId", speciesId);
        model.addAttribute("factsGrouped", grouped);
        model.addAttribute("factCategories", FactCategory.values());
        model.addAttribute("factCategoryLabels", CATEGORY_LABELS);
        if (!model.containsAttribute("factForm")) {
            model.addAttribute("factForm", emptyForm());
        }
        return FACTS_FRAGMENT;
    }

    private String renderSection(Long speciesId, Model model) {
        return populateFactsSection(speciesId,
                adminSpeciesFactService.getFactsGrouped(speciesId), model);
    }

    private static SpeciesFactFormDto emptyForm() {
        return new SpeciesFactFormDto(null, null, null, null, 0);
    }
}

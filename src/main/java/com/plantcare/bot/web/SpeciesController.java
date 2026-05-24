package com.plantcare.bot.web;

import com.plantcare.bot.service.SpeciesService;
import com.plantcare.bot.web.dto.PageResponse;
import com.plantcare.bot.web.dto.SpeciesDetailDto;
import com.plantcare.bot.web.dto.SpeciesSummaryDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Публичный REST API справочника видов растений.
 *
 * Аутентификация не требуется. Ответы кешируются в {@code species-list} / {@code species-detail}
 * (TTL 1 час); кеш сбрасывается при любом изменении через AdminSpeciesService.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/species")
@RequiredArgsConstructor
public class SpeciesController {

    private final SpeciesService speciesService;

    /**
     * Возвращает страницу видов с опциональной фильтрацией по подстроке.
     *
     * Параметр {@code size} ограничен значением 100 независимо от переданного значения.
     * Если {@code q} пустой или отсутствует, возвращаются все виды.
     *
     * @param q    подстрока для поиска по названию; пустая строка — без фильтра
     * @param page номер страницы (0-based)
     * @param size размер страницы; применяется {@code min(size, 100)}
     * @return страница с результатами и метаданными пагинации
     */
    @GetMapping
    public PageResponse<SpeciesSummaryDto> list(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        int effectiveSize = Math.min(size, 100);
        log.debug("Species list request. q='{}', page={}, size={}", q, page, effectiveSize);

        Page<SpeciesSummaryDto> speciesPage = speciesService.findPage(q, PageRequest.of(page, effectiveSize))
                .map(SpeciesSummaryDto::from);

        return new PageResponse<>(
                speciesPage.getContent(),
                speciesPage.getNumber(),
                speciesPage.getSize(),
                speciesPage.getTotalElements(),
                speciesPage.getTotalPages()
        );
    }

    /**
     * Возвращает детальную информацию о виде, включая поле {@code description}.
     *
     * @param id идентификатор вида
     * @return DTO с полными данными вида
     * @throws jakarta.persistence.EntityNotFoundException если вид не найден (→ 404)
     */
    @GetMapping("/{id}")
    public SpeciesDetailDto getById(@PathVariable Long id) {
        log.debug("Species detail request. id={}", id);
        return SpeciesDetailDto.from(speciesService.getById(id));
    }
}

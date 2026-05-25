package com.plantcare.bot.admin.service;

import com.plantcare.bot.admin.dto.SpeciesFormDto;
import com.plantcare.bot.admin.dto.SpeciesListItem;
import com.plantcare.bot.admin.exception.DuplicateSpeciesNameException;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.enums.CareDifficulty;
import com.plantcare.core.domain.enums.LightPreference;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.SpeciesRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSpeciesService {

    private static final int PAGE_SIZE = 50;

    /**
     * Whitelist для сортировки. Защита от ?sort=arbitraryField, который
     * упадёт на JPA-уровне с криповой ошибкой.
     */
    private static final Set<String> SORTABLE_FIELDS = Set.of(
            "name", "latinName", "wateringDays", "mistingDays",
            "fertilizingDays", "lightPreference", "careDifficulty", "popularity"
    );

    private static final String DEFAULT_SORT = "popularity";

    private final SpeciesRepository speciesRepository;
    private final PlantRepository plantRepository;

    /**
     * Возвращает страницу видов с числом привязанных растений для каждого.
     * Используем единичный запрос-агрегацию, чтобы избежать N+1.
     */
    @Transactional(readOnly = true)
    public Page<SpeciesListItem> findAllWithLinkedCounts(String query, int page,
                                                         String sortField, String sortDir) {
        Pageable pageable = buildPageable(page, sortField, sortDir);

        Page<Species> speciesPage = StringUtils.hasText(query)
                ? speciesRepository.findBySearch(query, pageable)
                : speciesRepository.findAll(pageable);

        if (speciesPage.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        // Один запрос для всех id текущей страницы
        List<Long> ids = speciesPage.getContent().stream().map(Species::getId).toList();
        Map<Long, Long> counts = plantRepository.countBySpeciesIdIn(ids).stream()
                .collect(Collectors.toMap(
                        PlantRepository.SpeciesPlantCount::getSpeciesId,
                        PlantRepository.SpeciesPlantCount::getPlantCount));

        List<SpeciesListItem> items = speciesPage.getContent().stream()
                .map(s -> SpeciesListItem.of(s, counts.getOrDefault(s.getId(), 0L)))
                .toList();

        return new PageImpl<>(items, pageable, speciesPage.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Species findById(Long id) {
        return speciesRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Вид не найден: " + id));
    }

    @Transactional
    @CacheEvict(cacheNames = {"species-list", "species-detail"}, allEntries = true)
    public Species create(SpeciesFormDto dto, String adminUsername) {
        validateUniqueName(null, dto.getName());

        Species species = new Species();
        applyDto(species, dto);
        Species saved = speciesRepository.save(species);

        log.info("Admin [{}] created species id={} name='{}'", adminUsername, saved.getId(), saved.getName());
        return saved;
    }

    @Transactional
    @CacheEvict(cacheNames = {"species-list", "species-detail"}, allEntries = true)
    public Species update(Long id, SpeciesFormDto dto, String adminUsername) {
        validateUniqueName(id, dto.getName());

        Species species = findById(id);
        applyDto(species, dto);
        Species saved = speciesRepository.save(species);

        log.info("Admin [{}] updated species id={} name='{}'", adminUsername, saved.getId(), saved.getName());
        return saved;
    }

    /**
     * Patch одного поля. Валидируем диапазоны вручную, потому что
     * Bean Validation на отдельные поля DTO здесь не применяется.
     */
    @Transactional
    @CacheEvict(cacheNames = {"species-list", "species-detail"}, allEntries = true)
    public Species patchField(Long id, String field, String rawValue, String adminUsername) {
        Species species = findById(id);

        switch (field) {
            case "name" -> {
                String trimmed = rawValue == null ? "" : rawValue.trim();
                if (trimmed.isEmpty()) {
                    throw new IllegalArgumentException("Название не может быть пустым");
                }
                if (trimmed.length() > 100) {
                    throw new IllegalArgumentException("Название не должно превышать 100 символов");
                }
                validateUniqueName(id, trimmed);
                species.setName(trimmed);
            }
            case "latinName" -> {
                if (rawValue != null && rawValue.length() > 150) {
                    throw new IllegalArgumentException("Латинское название не должно превышать 150 символов");
                }
                species.setLatinName(rawValue);
            }
            case "wateringDays"    -> species.setWateringDays(parseDayNumber(rawValue));
            case "mistingDays"     -> species.setMistingDays(parseDayNumber(rawValue));
            case "fertilizingDays" -> species.setFertilizingDays(parseDayNumber(rawValue));
            case "popularity"      -> species.setPopularity(parsePopularity(rawValue));
            case "lightPreference" -> species.setLightPreference(parseEnum(LightPreference.class, rawValue));
            case "careDifficulty"  -> species.setCareDifficulty(parseEnum(CareDifficulty.class, rawValue));
            default -> throw new IllegalArgumentException("Неизвестное поле: " + field);
        }

        Species saved = speciesRepository.save(species);
        log.info("Admin [{}] patched species id={} field='{}' value='{}'",
                adminUsername, id, field, rawValue);
        return saved;
    }

    @Transactional
    @CacheEvict(cacheNames = {"species-list", "species-detail"}, allEntries = true)
    public void delete(Long id, String adminUsername) {
        Species species = findById(id);
        speciesRepository.delete(species);
        log.info("Admin [{}] deleted species id={} name='{}'", adminUsername, id, species.getName());
    }

    @Transactional(readOnly = true)
    public long countLinkedPlants(Long speciesId) {
        return plantRepository.countBySpeciesId(speciesId);
    }

    // ------------------------------------------------------------------ helpers

    private Pageable buildPageable(int page, String sortField, String sortDir) {
        String safeField = SORTABLE_FIELDS.contains(sortField) ? sortField : DEFAULT_SORT;
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return PageRequest.of(Math.max(0, page), PAGE_SIZE, Sort.by(direction, safeField));
    }

    private void applyDto(Species s, SpeciesFormDto dto) {
        s.setName(dto.getName().trim());
        s.setLatinName(dto.getLatinName());
        s.setWateringDays(dto.getWateringDays());
        s.setMistingDays(dto.getMistingDays());
        s.setFertilizingDays(dto.getFertilizingDays());
        s.setLightPreference(dto.getLightPreference());
        s.setCareDifficulty(dto.getCareDifficulty());
        s.setDescription(dto.getDescription());
        s.setSearchTags(dto.getSearchTags());
        s.setPopularity(dto.getPopularity() == null ? 50 : dto.getPopularity());
        // Tri-state токсичность: null допустим (нет данных), в т.ч. сброс true→null.
        s.setToxicToCats(dto.getToxicToCats());
        s.setToxicToDogs(dto.getToxicToDogs());
        s.setToxicToHumans(dto.getToxicToHumans());
    }

    private void validateUniqueName(Long currentId, String name) {
        speciesRepository.findByNameIgnoreCase(name).ifPresent(existing -> {
            if (!existing.getId().equals(currentId)) {
                throw new DuplicateSpeciesNameException(name);
            }
        });
    }

    private Integer parseDayNumber(String value) {
        if (value == null || value.isBlank()) return null;
        int n;
        try {
            n = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Должно быть числом");
        }
        if (n < 1 || n > 365) {
            throw new IllegalArgumentException("Должно быть от 1 до 365");
        }
        return n;
    }

    private Integer parsePopularity(String value) {
        if (value == null || value.isBlank()) return 50;
        int n;
        try {
            n = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Должно быть числом");
        }
        if (n < 0 || n > 100) {
            throw new IllegalArgumentException("Популярность от 0 до 100");
        }
        return n;
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumClass, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(enumClass, value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Недопустимое значение: " + value);
        }
    }
}

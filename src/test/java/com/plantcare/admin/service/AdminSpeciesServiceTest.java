/*
package com.plantcare.admin.service;

import com.plantcare.admin.dto.SpeciesFormDto;
import com.plantcare.admin.dto.SpeciesListItem;
import com.plantcare.admin.exception.DuplicateSpeciesNameException;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.enums.CareDifficulty;
import com.plantcare.core.domain.enums.LightPreference;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.SpeciesRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

*/
/**
 * Интеграционные тесты AdminSpeciesService.
 *
 * NOTE: Если в проекте есть AbstractIntegrationTest с Testcontainers —
 * наследуй вместо @SpringBootTest. Подстрой под существующую инфраструктуру.
 *//*

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdminSpeciesServiceTest {

    @Autowired private AdminSpeciesService service;
    @Autowired private SpeciesRepository speciesRepository;
    @Autowired private PlantRepository plantRepository;

    private static final String ADMIN = "admin";

    // ============================================================ create

    @Test
    void create_validDto_persistsSpecies() {
        SpeciesFormDto dto = buildDto("Монстера тест", 7, 3, 30);

        Species created = service.create(dto, ADMIN);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getName()).isEqualTo("Монстера тест");
        assertThat(created.getWateringDays()).isEqualTo(7);
        assertThat(created.getPopularity()).isEqualTo(50);
    }

    @Test
    void create_withDuplicateName_throwsDuplicateSpeciesNameException() {
        service.create(buildDto("Фикус уникальный", 5, 2, 14), ADMIN);

        assertThatThrownBy(() -> service.create(buildDto("Фикус уникальный", 10, 5, 21), ADMIN))
                .isInstanceOf(DuplicateSpeciesNameException.class)
                .hasMessageContaining("Вид с таким именем уже существует");
    }

    @Test
    void create_withDuplicateName_caseInsensitive_throws() {
        service.create(buildDto("Алоэ Вера", 14, null, null), ADMIN);

        assertThatThrownBy(() -> service.create(buildDto("алоэ вера", 7, null, null), ADMIN))
                .isInstanceOf(DuplicateSpeciesNameException.class);
    }

    // ============================================================ update

   */
/* @Test
    void update_changesPersistedFields() {
        Species original = service.create(buildDto("Калатея", 3, 1, 7), ADMIN);

        SpeciesFormDto updateDto = buildDto("Калатея", 10, 2, 14);
        updateDto.setDescription("Обновлённое описание");
        service.update(original.getId(), updateDto, ADMIN);

        Species updated = service.findById(original.getId());
        assertThat(updated.getWateringDays()).isEqualTo(10);
        assertThat(updated.getDescription()).isEqualTo("Обновлённое описание");
    }*//*


    // ============================================================ patchField

    @Test
    void patchField_name_updatesOnlyName() {
        Species sp = service.create(buildDto("Хойя тест", 14, 3, 30), ADMIN);
        Integer originalWatering = sp.getWateringDays();
        Integer originalPopularity = sp.getPopularity();

        service.patchField(sp.getId(), "name", "Хойя обновлённая", ADMIN);

        Species patched = service.findById(sp.getId());
        assertThat(patched.getName()).isEqualTo("Хойя обновлённая");
        assertThat(patched.getWateringDays()).isEqualTo(originalWatering);
        assertThat(patched.getPopularity()).isEqualTo(originalPopularity);
    }

    @Test
    void patchField_wateringDays_outOfRange_throws() {
        Species sp = service.create(buildDto("Тест диапазона", 7, null, null), ADMIN);

        assertThatThrownBy(() -> service.patchField(sp.getId(), "wateringDays", "9999", ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("от 1 до 365");
    }

    @Test
    void patchField_popularity_outOfRange_throws() {
        Species sp = service.create(buildDto("Тест попу", 5, null, null), ADMIN);

        assertThatThrownBy(() -> service.patchField(sp.getId(), "popularity", "-50", ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void patchField_lightPreference_updatesEnum() {
        Species sp = service.create(buildDto("Пальма", 5, 2, 30), ADMIN);

        service.patchField(sp.getId(), "lightPreference", "DIRECT", ADMIN);

        assertThat(service.findById(sp.getId()).getLightPreference()).isEqualTo(LightPreference.DIRECT);
    }

   */
/* @Test
    void patchField_unknownField_throws() {
        Species sp = service.create(buildDto("Кактус", 30, null, null), ADMIN);

        assertThatThrownBy(() -> service.patchField(sp.getId(), "fakeField", "x", ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Неизвестное поле");
    }*//*


    // ============================================================ delete

    @Test
    void delete_speciesWithoutLinkedPlants_succeeds() {
        Species sp = service.create(buildDto("Редкий вид", 10, null, null), ADMIN);
        Long id = sp.getId();

        service.delete(id, ADMIN);

        assertThat(speciesRepository.findById(id)).isEmpty();
    }

    */
/*@Test
    void delete_speciesWithLinkedPlants_plantsGetNullSpeciesId() {
        Species sp = service.create(buildDto("Вид с растениями", 7, 2, 14), ADMIN);

        Plant plant = createPlantWithSpecies(sp.getId());
        assertThat(plant.getSpecies().getId()).isEqualTo(sp.getId());

        service.delete(sp.getId(), ADMIN);

        Plant reloaded = plantRepository.findById(plant.getId()).orElseThrow();
        assertThat(reloaded.getSpecies().getId()).isNull();
    }

    // ============================================================ search + listing

    @Test
    void findAllWithLinkedCounts_searchByTag_returnsMatch() {
        SpeciesFormDto dto = buildDto("Монстера", 7, 3, 30);
        dto.setSearchTags("монстера, monstera, лиана");
        service.create(dto, ADMIN);
        service.create(buildDto("Хлорофитум", 5, 2, 14), ADMIN);

        Page<SpeciesListItem> result = service.findAllWithLinkedCounts("monstera", 0, "popularity", "desc");

        assertThat(result.getContent())
                .extracting(SpeciesListItem::name)
                .containsExactly("Монстера");
    }*//*


   */
/* @Test
    void findAllWithLinkedCounts_searchByLatinName_returnsMatch() {
        SpeciesFormDto dto = buildDto("Бамбук домашний", 3, null, null);
        dto.setLatinName("Bambusa vulgaris");
        service.create(dto, ADMIN);
        service.create(buildDto("Кактус", 30, null, null), ADMIN);

        Page<SpeciesListItem> result = service.findAllWithLinkedCounts("Bambusa", 0, "name", "asc");

        assertThat(result.getContent())
                .hasSize(1)
                .extracting(SpeciesListItem::name)
                .containsExactly("Бамбук домашний");
    }

    @Test
    void findAllWithLinkedCounts_returnsCorrectLinkedPlantsCount() {
        Species sp = service.create(buildDto("С растениями", 7, null, null), ADMIN);
        createPlantWithSpecies(sp.getId());
        createPlantWithSpecies(sp.getId());

        Page<SpeciesListItem> result = service.findAllWithLinkedCounts("С растениями", 0, "popularity", "desc");

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).linkedPlantsCount()).isEqualTo(2);
    }*//*


    @Test
    void findAllWithLinkedCounts_unknownSortField_fallsBackToDefault() {
        service.create(buildDto("A", 5, null, null), ADMIN);
        service.create(buildDto("B", 7, null, null), ADMIN);

        // ?sort=fakeField не должно ломать запрос
        Page<SpeciesListItem> result = service.findAllWithLinkedCounts("", 0, "fakeField", "desc");

        assertThat(result.getContent()).isNotEmpty();
    }

    // ============================================================ helpers

    private SpeciesFormDto buildDto(String name, Integer wateringDays,
                                    Integer mistingDays, Integer fertilizingDays) {
        SpeciesFormDto dto = new SpeciesFormDto();
        dto.setName(name);
        dto.setWateringDays(wateringDays);
        dto.setMistingDays(mistingDays);
        dto.setFertilizingDays(fertilizingDays);
        dto.setPopularity(50);
        dto.setLightPreference(LightPreference.BRIGHT);
        dto.setCareDifficulty(CareDifficulty.EASY);
        return dto;
    }

    */
/**
     * Создаёт Plant с нужным speciesId.
     * NOTE: подстрой под реальный конструктор Plant в проекте
     * (например, обязательное поле userId или name).
     *//*

    private Plant createPlantWithSpecies(Long speciesId) {
        Plant plant = new Plant();
        //plant.setSpeciesId(speciesId);
        return plantRepository.save(plant);
    }
}
*/

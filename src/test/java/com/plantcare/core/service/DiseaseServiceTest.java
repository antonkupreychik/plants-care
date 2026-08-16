package com.plantcare.core.service;

import com.plantcare.core.diagnosis.DiagnosisSymptom;
import com.plantcare.core.domain.Disease;
import com.plantcare.core.repository.DiseaseRepository;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Тесты чтения справочника болезней и матчинга по симптому (issue #140).
 *
 * Опирается на сид V25__seed_diseases.sql (20 болезней), Testcontainers Postgres.
 * Матчинг по symptom_codes — строго по точному токену, проверяем happy + edge.
 */
class DiseaseServiceTest extends IntegrationTestBase {

    @Autowired
    private DiseaseService diseaseService;

    @Autowired
    private DiseaseRepository diseaseRepository;

    private Long fixtureId;

    @AfterEach
    void cleanupFixture() {
        if (fixtureId != null) {
            diseaseRepository.deleteById(fixtureId);
            fixtureId = null;
        }
    }

    @Test
    void should_match_strictly_by_token_and_not_by_substring_when_matchBySymptom() {
        // arrange: контролируемая фикстура с кодами строго "leaf_spots,other"
        Disease fixture = diseaseRepository.save(Disease.builder()
                .name("ZZZ Тестовая болезнь токенов")
                .symptoms("симптомы")
                .treatment("лечение")
                .prevention("профилактика")
                .symptomCodes("leaf_spots,other")
                .build());
        fixtureId = fixture.getId();

        // act
        List<DiseaseCard> byLeafSpots = diseaseService.matchBySymptom(DiagnosisSymptom.LEAF_SPOTS);
        List<DiseaseCard> byOther = diseaseService.matchBySymptom(DiagnosisSymptom.OTHER);
        List<DiseaseCard> byPests = diseaseService.matchBySymptom(DiagnosisSymptom.PESTS_OR_WEB);
        List<DiseaseCard> byWilting = diseaseService.matchBySymptom(DiagnosisSymptom.WILTING_LEAVES);

        // assert: точные токены матчат, прочие — нет (нет подстрочных ложных совпадений)
        assertThat(byLeafSpots).extracting(DiseaseCard::name).contains(fixture.getName());
        assertThat(byOther).extracting(DiseaseCard::name).contains(fixture.getName());
        assertThat(byPests).extracting(DiseaseCard::name).doesNotContain(fixture.getName());
        assertThat(byWilting).extracting(DiseaseCard::name).doesNotContain(fixture.getName());
    }

    @Test
    void should_return_empty_when_no_disease_has_symptom_code() {
        // arrange: единственная запись с этим кодом — удаляем после теста; но проще
        // проверить, что симптом без записей даёт пусто. BROWN_DRY_TIPS есть только
        // у неинфекционных; используем контролируемую фикстуру с другим набором.
        Disease fixture = diseaseRepository.save(Disease.builder()
                .name("ZZZ Болезнь без искомого кода")
                .symptoms("симптомы")
                .treatment("лечение")
                .prevention("профилактика")
                .symptomCodes("yellow_leaves")
                .build());
        fixtureId = fixture.getId();

        // act: матчим по коду, которого у фикстуры нет
        List<DiseaseCard> matches = diseaseService.matchBySymptom(DiagnosisSymptom.PESTS_OR_WEB);

        // assert: фикстуры среди совпадений нет
        assertThat(matches).extracting(DiseaseCard::name)
                .doesNotContain(fixture.getName());
    }

    @Test
    void should_return_all_diseases_ordered_by_name_when_getAll() {
        List<DiseaseCard> all = diseaseService.getAll();

        assertThat(all).hasSizeGreaterThanOrEqualTo(20);
        assertThat(all).extracting(DiseaseCard::name)
                .isSortedAccordingTo(String::compareTo);
    }

    @Test
    void should_return_full_card_when_getById_existing() {
        Disease scale = diseaseRepository.findAllByOrderByNameAsc().stream()
                .filter(d -> d.getName().equals("Щитовка"))
                .findFirst()
                .orElseThrow();

        DiseaseCard card = diseaseService.getById(scale.getId());

        assertThat(card.id()).isEqualTo(scale.getId());
        assertThat(card.name()).isEqualTo("Щитовка");
        assertThat(card.latinName()).isEqualTo("Coccidae");
        assertThat(card.symptoms()).isNotBlank();
        assertThat(card.treatment()).isNotBlank();
        assertThat(card.prevention()).isNotBlank();
    }

    @Test
    void should_throw_DiseaseNotFoundException_when_getById_missing() {
        assertThatThrownBy(() -> diseaseService.getById(999_999L))
                .isInstanceOf(DiseaseNotFoundException.class)
                .hasMessageContaining("999999");
    }

    @Test
    void should_match_pest_diseases_when_symptom_is_pests_or_web() {
        List<DiseaseCard> matches = diseaseService.matchBySymptom(DiagnosisSymptom.PESTS_OR_WEB);

        assertThat(matches).extracting(DiseaseCard::name)
                .contains("Паутинный клещ", "Щитовка", "Мучнистый червец", "Тля", "Белокрылка");
    }

    @Test
    void should_match_spot_diseases_when_symptom_is_leaf_spots() {
        List<DiseaseCard> matches = diseaseService.matchBySymptom(DiagnosisSymptom.LEAF_SPOTS);

        assertThat(matches).extracting(DiseaseCard::name)
                .contains("Мучнистая роса", "Антракноз", "Ржавчина");
        // У вредителя без leaf_spots в кодах — не матчится
        assertThat(matches).extracting(DiseaseCard::name)
                .doesNotContain("Тля");
    }

    @Test
    void should_return_matches_ordered_by_name_when_matchBySymptom() {
        List<DiseaseCard> matches = diseaseService.matchBySymptom(DiagnosisSymptom.YELLOW_LEAVES);

        assertThat(matches).extracting(DiseaseCard::name)
                .isSortedAccordingTo(String::compareTo);
    }

    @Test
    void should_return_empty_when_symptom_is_null() {
        List<DiseaseCard> matches = diseaseService.matchBySymptom(null);

        assertThat(matches).isEmpty();
    }

    @Test
    void should_return_empty_when_search_query_is_blank() {
        assertThat(diseaseService.search("  ", 10)).isEmpty();
        assertThat(diseaseService.search(null, 10)).isEmpty();
    }

    @Test
    void should_find_card_when_search_matches() {
        List<DiseaseCard> results = diseaseService.search("клещ", 10);

        assertThat(results).extracting(DiseaseCard::name)
                .contains("Паутинный клещ");
    }

    // ------------------------------------------------------------------ findPage / searchPage (issue #255)

    @Test
    void should_return_paged_result_when_findPage() {
        Page<DiseaseCard> page = diseaseService.findPage(PageRequest.of(0, 5));

        assertThat(page.getContent()).hasSizeLessThanOrEqualTo(5);
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(20);
        assertThat(page.getContent()).extracting(DiseaseCard::name)
                .isSortedAccordingTo(String::compareTo);
    }

    @Test
    void should_return_second_page_when_offset_applied() {
        Page<DiseaseCard> firstPage = diseaseService.findPage(PageRequest.of(0, 5));
        Page<DiseaseCard> secondPage = diseaseService.findPage(PageRequest.of(1, 5));

        assertThat(firstPage.getContent()).isNotEqualTo(secondPage.getContent());
        assertThat(secondPage.getNumber()).isEqualTo(1);
    }

    @Test
    void should_search_by_name_paged_when_searchPage() {
        Page<DiseaseCard> page = diseaseService.searchPage("клещ", PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(DiseaseCard::name)
                .contains("Паутинный клещ");
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void should_search_by_symptom_word_paged_when_searchPage() {
        // «налёт» присутствует в symptoms/search_tags нескольких грибковых болезней
        Page<DiseaseCard> page = diseaseService.searchPage("налёт", PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(DiseaseCard::name)
                .contains("Мучнистая роса");
    }

    @Test
    void should_return_empty_page_when_searchPage_blank_query() {
        Page<DiseaseCard> page = diseaseService.searchPage("  ", PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }
}

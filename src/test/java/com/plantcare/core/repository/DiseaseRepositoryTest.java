package com.plantcare.core.repository;

import com.plantcare.core.domain.Disease;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты полнотекстового поиска по справочнику болезней (issue #140).
 *
 * FTS (to_tsvector/plainto_tsquery) и GIN-индекс работают только на реальном
 * Postgres — поэтому Testcontainers, не H2. Данные берутся из сида
 * V25__seed_diseases.sql, поэтому read-only: записи не создаём, очистку не делаем.
 */
class DiseaseRepositoryTest extends IntegrationTestBase {

    @Autowired
    private DiseaseRepository diseaseRepository;

    @Test
    void should_find_spider_mite_when_searching_by_name_word() {
        List<Disease> results = diseaseRepository.searchByQuery("клещ", 10);

        assertThat(results).extracting(Disease::getName)
                .contains("Паутинный клещ");
    }

    @Test
    void should_find_disease_when_searching_by_symptom_word() {
        // «налёт» встречается в symptoms/search_tags нескольких грибковых болезней
        List<Disease> results = diseaseRepository.searchByQuery("налёт", 20);

        assertThat(results).extracting(Disease::getName)
                .contains("Мучнистая роса");
    }

    @Test
    void should_find_disease_when_searching_by_english_search_tag() {
        // В search_tags «Паутинного клеща» есть "spider mite"
        List<Disease> results = diseaseRepository.searchByQuery("spider", 10);

        assertThat(results).extracting(Disease::getName)
                .contains("Паутинный клещ");
    }

    @Test
    void should_find_disease_when_searching_by_partial_name_via_like_fallback() {
        // LIKE-фолбэк по части русского названия
        List<Disease> results = diseaseRepository.searchByQuery("ржав", 10);

        assertThat(results).extracting(Disease::getName)
                .contains("Ржавчина");
    }

    @Test
    void should_find_disease_when_searching_by_latin_name() {
        // LIKE-фолбэк по латинскому имени
        List<Disease> results = diseaseRepository.searchByQuery("Botrytis", 10);

        assertThat(results).extracting(Disease::getName)
                .contains("Серая гниль");
    }

    @Test
    void should_return_empty_when_query_has_no_match() {
        List<Disease> results = diseaseRepository.searchByQuery("xyznonexistentболезнь", 10);

        assertThat(results).isEmpty();
    }

    @Test
    void should_limit_results_when_maxResults_is_smaller_than_match_count() {
        // «гниль» матчит несколько записей (корневая/серая гниль и т.п.)
        List<Disease> limited = diseaseRepository.searchByQuery("гниль", 2);

        assertThat(limited).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void should_return_results_ordered_by_name_when_searching() {
        List<Disease> results = diseaseRepository.searchByQuery("гниль", 20);

        assertThat(results).extracting(Disease::getName)
                .isSortedAccordingTo(String::compareTo);
    }

    @Test
    void should_return_all_diseases_ordered_by_name_asc() {
        List<Disease> all = diseaseRepository.findAllByOrderByNameAsc();

        assertThat(all).extracting(Disease::getName)
                .isSortedAccordingTo(String::compareTo);
        assertThat(all).hasSizeGreaterThanOrEqualTo(20);
    }

    // ------------------------------------------------------------------ paginated queries (issue #255)

    @Test
    void should_return_page_of_all_diseases_when_pageable() {
        Page<Disease> page = diseaseRepository.findAllByOrderByNameAsc(PageRequest.of(0, 5));

        assertThat(page.getContent()).hasSizeLessThanOrEqualTo(5);
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(20);
        assertThat(page.getContent()).extracting(Disease::getName)
                .isSortedAccordingTo(String::compareTo);
    }

    @Test
    void should_return_second_page_different_from_first_when_pageable() {
        Page<Disease> firstPage = diseaseRepository.findAllByOrderByNameAsc(PageRequest.of(0, 5));
        Page<Disease> secondPage = diseaseRepository.findAllByOrderByNameAsc(PageRequest.of(1, 5));

        assertThat(firstPage.getContent()).extracting(Disease::getName)
                .doesNotContainAnyElementsOf(
                        secondPage.getContent().stream().map(Disease::getName).toList());
    }

    @Test
    void should_find_spider_mite_when_searching_by_name_word_paginated() {
        Page<Disease> page = diseaseRepository.searchByQuery("клещ", PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(Disease::getName)
                .contains("Паутинный клещ");
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void should_find_disease_by_symptom_word_paginated() {
        Page<Disease> page = diseaseRepository.searchByQuery("налёт", PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(Disease::getName)
                .contains("Мучнистая роса");
    }

    @Test
    void should_return_empty_page_when_no_match_paginated() {
        Page<Disease> page = diseaseRepository.searchByQuery("xyznonexistentболезнь", PageRequest.of(0, 10));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }
}

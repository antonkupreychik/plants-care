package com.plantcare.bot.repository;

import com.plantcare.bot.domain.Disease;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DiseaseRepository extends JpaRepository<Disease, Long> {

    /**
     * Все болезни в алфавитном порядке для списка в справочнике (issue #140).
     * Справочник небольшой (~20 записей, ADR-013), пагинация не нужна.
     */
    List<Disease> findAllByOrderByNameAsc();

    /**
     * Полнотекстовый поиск по названию, тегам и симптомам.
     * Использует GIN-индекс {@code idx_diseases_search}, созданный в
     * {@code V24__create_diseases.sql} на том же выражении to_tsvector.
     *
     * <p>Запрос native — Spring Data не умеет tsvector декларативно. Паттерн
     * скопирован с {@link SpeciesRepository#searchByQuery}: plainto_tsquery
     * толерантен к пользовательскому вводу (экранирует спецсимволы), плюс
     * LIKE-фолбэк по названию и латинскому имени для частичных совпадений.
     */
    @Query(value = """
        SELECT * FROM diseases
        WHERE to_tsvector('simple',
                  coalesce(name, '') || ' ' || coalesce(search_tags, '') || ' ' || coalesce(symptoms, ''))
              @@ plainto_tsquery('simple', :query)
           OR LOWER(name) LIKE LOWER(CONCAT('%', :query, '%'))
           OR LOWER(coalesce(latin_name, '')) LIKE LOWER(CONCAT('%', :query, '%'))
        ORDER BY name ASC
        LIMIT :maxResults
        """, nativeQuery = true)
    List<Disease> searchByQuery(@Param("query") String query, @Param("maxResults") int maxResults);
}

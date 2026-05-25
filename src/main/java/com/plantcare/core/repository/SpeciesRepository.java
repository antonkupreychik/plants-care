package com.plantcare.core.repository;

import com.plantcare.core.domain.Species;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpeciesRepository extends JpaRepository<Species, Long> {

    Optional<Species> findByName(String name);

    /**
     * Топ популярных видов для показа в первом экране выбора.
     * Используется в задаче #8.
     */
    List<Species> findAllByOrderByPopularityDesc(Limit limit);

    /**
     * Полнотекстовый поиск по имени, латинскому названию и тегам.
     * Использует GIN-индекс на to_tsvector(search_tags), созданный в V1__init_schema.sql.
     *
     * Запрос native, потому что Spring Data не умеет tsvector декларативно.
     * Plainto_tsquery толерантен к пользовательскому вводу — экранирует спец-символы,
     * в отличие от to_tsquery, который требует чистого формата.
     *
     * issue #129: вид также находится по совпадению в теле/заголовке его
     * энциклопедических фактов (species_facts) — через EXISTS с tsvector по
     * coalesce(title,'')||' '||body. Ранжирование по popularity сохранено.
     */
    @Query(value = """
        SELECT * FROM species
        WHERE to_tsvector('simple', coalesce(search_tags, '')) @@ plainto_tsquery('simple', :query)
           OR LOWER(name) LIKE LOWER(CONCAT('%', :query, '%'))
           OR LOWER(coalesce(latin_name, '')) LIKE LOWER(CONCAT('%', :query, '%'))
           OR EXISTS (
                SELECT 1 FROM species_facts f
                WHERE f.species_id = species.id
                  AND to_tsvector('simple', coalesce(f.title, '') || ' ' || f.body)
                      @@ plainto_tsquery('simple', :query)
           )
        ORDER BY popularity DESC
        LIMIT :maxResults
        """, nativeQuery = true)
    List<Species> searchByQuery(@Param("query") String query, @Param("maxResults") int maxResults);


    /**
     * Поиск по name, latin_name и search_tags (case-insensitive).
     */
    @Query("""
            SELECT s FROM Species s
            WHERE LOWER(s.name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(s.latinName) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(s.searchTags) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<Species> findBySearch(@Param("q") String query, Pageable pageable);

    /**
     * Нужен для проверки уникальности имени при create/update.
     */
    Optional<Species> findByNameIgnoreCase(String name);
}

package com.plantcare.bot.repository;

import com.plantcare.bot.domain.Species;
import org.springframework.data.domain.Limit;
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
     */
    @Query(value = """
        SELECT * FROM species
        WHERE to_tsvector('simple', coalesce(search_tags, '')) @@ plainto_tsquery('simple', :query)
           OR LOWER(name) LIKE LOWER(CONCAT('%', :query, '%'))
           OR LOWER(coalesce(latin_name, '')) LIKE LOWER(CONCAT('%', :query, '%'))
        ORDER BY popularity DESC
        LIMIT :maxResults
        """, nativeQuery = true)
    List<Species> searchByQuery(@Param("query") String query, @Param("maxResults") int maxResults);
}

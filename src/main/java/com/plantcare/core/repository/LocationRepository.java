package com.plantcare.core.repository;

import com.plantcare.core.domain.Location;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface LocationRepository extends JpaRepository<Location, Long> {

    List<Location> findAllByUserIdOrderByDefaultLocationAscCreatedAtAsc(Long userId);

    Optional<Location> findByUserIdAndId(Long userId, Long locationId);

    Optional<Location> findByUserIdAndDefaultLocationTrue(Long userId);

    boolean existsByUserIdAndNameIgnoreCase(Long userId, String name);

    boolean existsByUserIdAndNameIgnoreCaseAndIdNot(Long userId, String name, Long id);

    long countByUserId(Long userId);

    // ===== Офлайн-синк (issue #91) =====

    /**
     * Активные (не удалённые) локации юзера, изменённые после {@code since}.
     */
    @Query("""
            SELECT l FROM Location l
            WHERE l.user.id = :userId
              AND l.deletedAt IS NULL
              AND l.updatedAt > :since
            ORDER BY l.updatedAt ASC
            """)
    List<Location> findChangedSince(
            @Param("userId") Long userId,
            @Param("since") LocalDateTime since
    );

    /**
     * Удалённые локации юзера (deletedAt IS NOT NULL), удалённые после {@code since}.
     */
    @Query("""
            SELECT l.id AS id, l.deletedAt AS deletedAt
            FROM Location l
            WHERE l.user.id = :userId
              AND l.deletedAt IS NOT NULL
              AND l.deletedAt > :since
            """)
    List<DeletedRecord> findDeletedSince(
            @Param("userId") Long userId,
            @Param("since") LocalDateTime since
    );

    /** Проекция для списка deletions в sync-ответе (issue #91). */
    interface DeletedRecord {
        Long getId();
        LocalDateTime getDeletedAt();
    }
}
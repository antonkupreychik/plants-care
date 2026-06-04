package com.plantcare.core.repository;

import com.plantcare.core.domain.SharingInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SharingInviteRepository extends JpaRepository<SharingInvite, Long> {

    /**
     * Приглашения, выпущенные владельцем, с подтянутым набором {@code plantIds},
     * чтобы маппинг в DTO вне транзакции не упирался в {@code no Session}.
     * {@code DISTINCT} — из-за JOIN на коллекцию растений.
     */
    @Query("""
            SELECT DISTINCT i FROM SharingInvite i
            LEFT JOIN FETCH i.plantIds
            WHERE i.inviter.id = :userId
            ORDER BY i.id ASC
            """)
    List<SharingInvite> findAllByInviterIdWithPlants(@Param("userId") Long userId);
}

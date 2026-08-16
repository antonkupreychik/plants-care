package com.plantcare.core.repository;

import com.plantcare.core.domain.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoomRepository extends JpaRepository<Room, Long> {

    List<Room> findAllByUserIdOrderByDisplayOrderAscNameAsc(Long userId);

    boolean existsByUserIdAndName(Long userId, String name);

    // ---- REST API rooms-in-location (issue #283) ----

    List<Room> findAllByUserIdAndLocationIdOrderByDisplayOrderAscNameAsc(Long userId, Long locationId);

    Optional<Room> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndLocationIdAndNameIgnoreCase(Long userId, Long locationId, String name);

    boolean existsByUserIdAndLocationIdAndNameIgnoreCaseAndIdNot(Long userId, Long locationId, String name, Long excludeId);

    long countByUserIdAndLocationId(Long userId, Long locationId);

    boolean existsByUserIdAndId(Long userId, Long id);
}

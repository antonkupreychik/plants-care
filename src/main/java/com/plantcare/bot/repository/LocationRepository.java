package com.plantcare.bot.repository;

import com.plantcare.bot.domain.Location;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
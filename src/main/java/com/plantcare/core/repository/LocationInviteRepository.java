package com.plantcare.core.repository;

import com.plantcare.core.domain.LocationInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LocationInviteRepository extends JpaRepository<LocationInvite, Long> {

    Optional<LocationInvite> findByToken(String token);
}

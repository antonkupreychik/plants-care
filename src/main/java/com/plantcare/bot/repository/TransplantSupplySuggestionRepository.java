package com.plantcare.bot.repository;

import com.plantcare.bot.domain.TransplantSupplySuggestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TransplantSupplySuggestionRepository
        extends JpaRepository<TransplantSupplySuggestion, Long> {

    /**
     * «Уже подсказывали по этой предстоящей пересадке?» — проверка идемпотентности
     * перед созданием новой подсказки. Совпадает с UNIQUE
     * {@code (user_id, plant_id, source_event_id)} из V29.
     */
    boolean existsByUserIdAndPlantIdAndSourceEventId(Long userId, Long plantId, Long sourceEventId);

    /**
     * Подсказка, скоупленная по владельцу — чтобы юзер не смог тронуть чужую
     * строку через подделанный callback_data.
     */
    Optional<TransplantSupplySuggestion> findByUserIdAndId(Long userId, Long id);
}

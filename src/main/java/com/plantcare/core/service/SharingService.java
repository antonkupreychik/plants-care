package com.plantcare.core.service;

import com.plantcare.core.domain.SharingInvite;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.SharingInviteRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Совместный уход (issue #191, mobile G22, экран 26).
 *
 * <p>Владелец приглашает соухаживающего по контакту, выбирает набор своих
 * растений и решает, может ли приглашённый отмечать уход. Приглашение стартует
 * в статусе {@code PENDING}; приём приглашения — отдельная задача.
 *
 * <p>Модель доступа: растения в приглашении обязаны принадлежать владельцу
 * (активные, не архивные) — иначе нельзя выдать доступ к чужому/несуществующему
 * растению (404).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SharingService {

    public static final String PLANTS_NOT_FOUND =
            "Некоторые растения не найдены или не принадлежат пользователю";

    private final SharingInviteRepository sharingInviteRepository;
    private final PlantRepository plantRepository;

    /** Приглашения, выпущенные владельцем, с набором растений каждого. */
    @Transactional(readOnly = true)
    public List<SharingInvite> listMembers(Long inviterUserId) {
        return sharingInviteRepository.findAllByInviterIdWithPlants(inviterUserId);
    }

    /**
     * Создаёт приглашение на набор растений с правом {@code canLogCare}.
     * Контакт нормализуется (trim); пустой контакт или пустой набор растений —
     * {@link IllegalArgumentException} (400). Если хотя бы одно растение чужое
     * или не существует — {@link EntityNotFoundException} (404).
     */
    @Transactional
    public SharingInvite createInvite(User inviter, List<Long> plantIds, String rawContact, boolean canLogCare) {
        String contact = rawContact == null ? "" : rawContact.trim();
        if (contact.isBlank()) {
            throw new IllegalArgumentException("Контакт приглашённого не может быть пустым");
        }
        if (plantIds == null || plantIds.isEmpty()) {
            throw new IllegalArgumentException("Нужно выбрать хотя бы одно растение");
        }

        Set<Long> uniquePlantIds = new LinkedHashSet<>(plantIds);

        long owned = plantRepository.countByUserIdAndArchivedAtIsNullAndIdIn(
                inviter.getId(), uniquePlantIds);
        if (owned != uniquePlantIds.size()) {
            throw new EntityNotFoundException(PLANTS_NOT_FOUND);
        }

        SharingInvite saved = sharingInviteRepository.save(
                new SharingInvite(inviter, contact, canLogCare, uniquePlantIds));

        log.info("Created sharing invite {} for contact {} by user {} on {} plants (canLogCare={})",
                saved.getId(), contact, inviter.getId(), uniquePlantIds.size(), canLogCare);

        return saved;
    }
}

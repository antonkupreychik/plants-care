package com.plantcare.api.v1;

import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.generated.LocationSharingApi;
import com.plantcare.api.generated.model.AcceptInviteResponse;
import com.plantcare.api.generated.model.LocationAccessRole;
import com.plantcare.api.generated.model.LocationInviteResponse;
import com.plantcare.api.generated.model.LocationMemberDto;
import com.plantcare.api.generated.model.LocationMembersResponse;
import com.plantcare.api.generated.model.SharedLocationDto;
import com.plantcare.api.generated.model.SharedLocationsResponse;
import com.plantcare.core.domain.LocationInvite;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.AccessRole;
import com.plantcare.core.service.LocationSharingService;
import com.plantcare.core.service.LocationSharingService.AcceptResult;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZoneOffset;
import java.util.List;

/**
 * REST API совместного ухода по локации (issue #251).
 *
 * <p>Документация и mapping живут в сгенерированном {@link LocationSharingApi}
 * (см. openapi/resources/location-sharing.yaml).
 *
 * <p>Поток:
 * <ol>
 *   <li>OWNER → POST /locations/{id}/invites → получает одноразовый токен.</li>
 *   <li>Приглашённый → POST /invites/{token}/accept → становится CARETAKER.</li>
 *   <li>GET /locations/{id}/members → список участников.</li>
 *   <li>DELETE /locations/{id}/members/{userId} → отзыв доступа (только OWNER).</li>
 *   <li>GET /shared-locations → локации, где я CARETAKER.</li>
 * </ol>
 */
@RestController
@RequiredArgsConstructor
public class LocationSharingController implements LocationSharingApi {

    private final LocationSharingService locationSharingService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * POST /api/v1/locations/{id}/invites — создать одноразовый инвайт (только OWNER).
     * Возвращает токен и время истечения.
     */
    @Override
    public LocationInviteResponse createLocationInvite(Long id) {
        Long userId = currentUserProvider.currentUserId();
        LocationInvite invite;
        try {
            invite = locationSharingService.createInvite(userId, id);
        } catch (IllegalArgumentException e) {
            throw new EntityNotFoundException(e.getMessage());
        }
        return new LocationInviteResponse()
                .token(invite.getToken())
                .expiresAt(invite.getExpiresAt().atOffset(ZoneOffset.UTC));
    }

    /**
     * POST /api/v1/invites/{token}/accept — принять приглашение.
     * Идемпотентно: повторный accept тем же пользователем → 200 (ALREADY_USED трактуется
     * как «у тебя уже есть доступ»). Протухший/использованный другим → 410.
     */
    @Override
    public AcceptInviteResponse acceptLocationInvite(String token) {
        User user = currentUserProvider.currentUser();
        AcceptResult result = locationSharingService.acceptInvite(token, user);

        return switch (result.status()) {
            // ACCEPTED покрывает оба кейса: новый accept и идемпотентный (тот же user)
            case ACCEPTED -> new AcceptInviteResponse()
                    .locationId(result.locationId())
                    .locationName(result.locationName())
                    .role(toApiRole(result.role() != null ? result.role() : AccessRole.CARETAKER));
            // ALREADY_USED — токен использован другим пользователем → 410 Gone
            case ALREADY_USED -> throw new ResponseStatusException(HttpStatus.GONE,
                    "Токен уже использован другим пользователем");
            case NOT_FOUND -> throw new EntityNotFoundException("Токен не найден");
            case EXPIRED -> throw new ResponseStatusException(HttpStatus.GONE, "Токен просрочен");
            case OWN_INVITE -> throw new AccessDeniedException("Нельзя принять собственное приглашение");
        };
    }

    /**
     * GET /api/v1/locations/{id}/members — список участников локации (OWNER + CARETAKER'ы).
     */
    @Override
    public LocationMembersResponse listLocationMembers(Long id) {
        Long userId = currentUserProvider.currentUserId();
        List<LocationMemberDto> members = locationSharingService.listMembers(userId, id).stream()
                .map(m -> new LocationMemberDto()
                        .userId(m.userId())
                        .displayName(m.displayName())
                        .role(toApiRole(m.role()))
                        .joinedAt(m.joinedAt().atOffset(ZoneOffset.UTC)))
                .toList();
        return new LocationMembersResponse().members(members);
    }

    /**
     * DELETE /api/v1/locations/{id}/members/{userId} — отозвать доступ (только OWNER).
     */
    @Override
    public void revokeLocationAccess(Long id, Long userId) {
        Long callerUserId = currentUserProvider.currentUserId();
        locationSharingService.revokeAccess(callerUserId, id, userId);
    }

    /**
     * GET /api/v1/shared-locations — локации, где у меня доступ CARETAKER.
     */
    @Override
    public SharedLocationsResponse listSharedLocations() {
        Long userId = currentUserProvider.currentUserId();
        List<SharedLocationDto> locations = locationSharingService.listSharedLocations(userId).stream()
                .map(info -> new SharedLocationDto()
                        .id(info.locationId())
                        .name(info.name())
                        .emoji(info.emoji())
                        .ownerUserId(info.ownerUserId())
                        .role(toApiRole(info.role()))
                        .joinedAt(info.joinedAt().atOffset(ZoneOffset.UTC)))
                .toList();
        return new SharedLocationsResponse().locations(locations);
    }

    private static LocationAccessRole toApiRole(AccessRole role) {
        return switch (role) {
            case OWNER -> LocationAccessRole.OWNER;
            case CARETAKER -> LocationAccessRole.CARETAKER;
        };
    }
}

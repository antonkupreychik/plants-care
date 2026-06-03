package com.plantcare.api.v1;

import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.generated.SharingApi;
import com.plantcare.api.generated.model.SharingInviteCreateRequest;
import com.plantcare.api.generated.model.SharingMemberDto;
import com.plantcare.api.generated.model.SharingMembersResponse;
import com.plantcare.api.generated.model.SharingStatus;
import com.plantcare.core.domain.SharingInvite;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.SharingService;
import com.plantcare.core.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * REST API совместного ухода (issue #191, mobile G22, экран 26).
 *
 * <p>Документация и mapping живут в сгенерированном {@link SharingApi}
 * (см. openapi/resources/sharing.yaml).
 */
@RestController
@RequiredArgsConstructor
public class SharingController implements SharingApi {

    private final SharingService sharingService;
    private final UserService userService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public SharingMembersResponse listSharingMembers() {
        Long userId = currentUserProvider.currentUserId();
        return new SharingMembersResponse(
                sharingService.listMembers(userId).stream()
                        .map(SharingController::toDto)
                        .toList()
        );
    }

    @Override
    public SharingMemberDto createSharingInvite(SharingInviteCreateRequest request) {
        User user = userService.getByIdOrThrow(currentUserProvider.currentUserId());
        boolean canLogCare = Boolean.TRUE.equals(request.getCanLogCare());
        SharingInvite invite = sharingService.createInvite(
                user, request.getPlantIds(), request.getInviteeContact(), canLogCare);
        return toDto(invite);
    }

    private static SharingMemberDto toDto(SharingInvite invite) {
        List<Long> plantIds = new ArrayList<>(invite.getPlantIds());
        plantIds.sort(Long::compareTo);
        return new SharingMemberDto(
                invite.getId(),
                invite.getInviteeContact(),
                SharingStatus.fromValue(invite.getStatus().name()),
                invite.isCanLogCare(),
                plantIds
        );
    }
}

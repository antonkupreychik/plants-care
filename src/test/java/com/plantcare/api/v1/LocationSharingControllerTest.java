package com.plantcare.api.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.AccessRole;
import com.plantcare.core.service.LocationSharingService;
import com.plantcare.core.service.LocationSharingService.AcceptResult;
import com.plantcare.core.service.LocationSharingService.MemberInfo;
import com.plantcare.core.service.LocationSharingService.SharedLocationInfo;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} для {@link LocationSharingController} — покрывает все 5 эндпоинтов
 * шеринга локаций по REST API (issue #251): создание инвайта, принятие токена,
 * список участников, отзыв доступа, список расшаренных локаций.
 */
@WebMvcTest(LocationSharingController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class LocationSharingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LocationSharingService locationSharingService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    private static final long CURRENT_USER_ID = 1L;
    private static final Instant FIXED_INSTANT = Instant.parse("2025-01-15T10:00:00Z");

    @BeforeEach
    void stubCurrentUser() {
        when(currentUserProvider.currentUserId()).thenReturn(CURRENT_USER_ID);
    }

    // ========================= POST /locations/{id}/invites =========================

    @Test
    void should_create_invite_and_return_201_when_owner() throws Exception {
        // arrange
        var invite = mock(com.plantcare.core.domain.LocationInvite.class);
        when(invite.getToken()).thenReturn("tok123abc");
        when(invite.getExpiresAt()).thenReturn(FIXED_INSTANT);
        when(locationSharingService.createInvite(CURRENT_USER_ID, 7L)).thenReturn(invite);

        // act + assert
        mockMvc.perform(post("/api/v1/locations/7/invites"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("tok123abc"))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void should_return_404_when_creating_invite_for_non_owned_location() throws Exception {
        // arrange — createInvite throws IllegalArgumentException for non-owned location
        when(locationSharingService.createInvite(CURRENT_USER_ID, 99L))
                .thenThrow(new IllegalArgumentException("Комната не найдена"));

        // act + assert
        mockMvc.perform(post("/api/v1/locations/99/invites"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ========================= POST /invites/{token}/accept =========================

    @Test
    void should_accept_invite_and_return_200_on_success() throws Exception {
        // arrange
        User user = User.builder().build();
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(locationSharingService.acceptInvite("tok123abc", user))
                .thenReturn(AcceptResult.accepted("🪴 Гостиная", 7L, AccessRole.CARETAKER));

        // act + assert
        mockMvc.perform(post("/api/v1/invites/tok123abc/accept"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value(7))
                .andExpect(jsonPath("$.locationName").value("🪴 Гостиная"))
                .andExpect(jsonPath("$.role").value("CARETAKER"));
    }

    @Test
    void should_return_404_when_token_not_found() throws Exception {
        // arrange
        User user = User.builder().build();
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(locationSharingService.acceptInvite("unknown", user))
                .thenReturn(AcceptResult.notFound());

        // act + assert
        mockMvc.perform(post("/api/v1/invites/unknown/accept"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void should_return_410_when_token_expired() throws Exception {
        // arrange
        User user = User.builder().build();
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(locationSharingService.acceptInvite("expiredtok", user))
                .thenReturn(AcceptResult.expired());

        // act + assert
        mockMvc.perform(post("/api/v1/invites/expiredtok/accept"))
                .andExpect(status().isGone());
    }

    @Test
    void should_return_410_when_token_already_used_by_another_user() throws Exception {
        // arrange
        User user = User.builder().build();
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(locationSharingService.acceptInvite("usedtok", user))
                .thenReturn(AcceptResult.alreadyUsed());

        // act + assert
        mockMvc.perform(post("/api/v1/invites/usedtok/accept"))
                .andExpect(status().isGone());
    }

    @Test
    void should_return_403_when_owner_accepts_own_invite() throws Exception {
        // arrange
        User user = User.builder().build();
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(locationSharingService.acceptInvite("owntok", user))
                .thenReturn(AcceptResult.ownInvite("🪴 Гостиная"));

        // act + assert
        mockMvc.perform(post("/api/v1/invites/owntok/accept"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    void should_return_200_idempotent_when_same_user_accepts_again() throws Exception {
        // arrange — same user accepts again → service returns ACCEPTED (idempotent)
        User user = User.builder().build();
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(locationSharingService.acceptInvite("tok123abc", user))
                .thenReturn(AcceptResult.accepted("🪴 Гостиная", 7L, AccessRole.CARETAKER));

        // act + assert
        mockMvc.perform(post("/api/v1/invites/tok123abc/accept"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value(7))
                .andExpect(jsonPath("$.role").value("CARETAKER"));
    }

    // ========================= GET /locations/{id}/members =========================

    @Test
    void should_return_members_including_owner_and_caretakers() throws Exception {
        // arrange
        List<MemberInfo> members = List.of(
                new MemberInfo(1L, "@owner", AccessRole.OWNER, FIXED_INSTANT),
                new MemberInfo(2L, "@anna", AccessRole.CARETAKER, FIXED_INSTANT)
        );
        when(locationSharingService.listMembers(CURRENT_USER_ID, 7L)).thenReturn(members);

        // act + assert
        mockMvc.perform(get("/api/v1/locations/7/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members").isArray())
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[0].userId").value(1))
                .andExpect(jsonPath("$.members[0].displayName").value("@owner"))
                .andExpect(jsonPath("$.members[0].role").value("OWNER"))
                .andExpect(jsonPath("$.members[1].userId").value(2))
                .andExpect(jsonPath("$.members[1].role").value("CARETAKER"));
    }

    @Test
    void should_return_403_when_listing_members_without_access() throws Exception {
        // arrange
        when(locationSharingService.listMembers(CURRENT_USER_ID, 99L))
                .thenThrow(new AccessDeniedException("Нет доступа к локации"));

        // act + assert
        mockMvc.perform(get("/api/v1/locations/99/members"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    void should_return_404_when_listing_members_of_nonexistent_location() throws Exception {
        // arrange
        when(locationSharingService.listMembers(CURRENT_USER_ID, 404L))
                .thenThrow(new EntityNotFoundException("Локация не найдена: 404"));

        // act + assert
        mockMvc.perform(get("/api/v1/locations/404/members"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ========================= DELETE /locations/{id}/members/{userId} =========================

    @Test
    void should_revoke_access_and_return_204_when_owner() throws Exception {
        // arrange
        doNothing().when(locationSharingService).revokeAccess(CURRENT_USER_ID, 7L, 2L);

        // act + assert
        mockMvc.perform(delete("/api/v1/locations/7/members/2"))
                .andExpect(status().isNoContent());
    }

    @Test
    void should_return_403_when_caretaker_tries_to_revoke() throws Exception {
        // arrange
        doThrow(new AccessDeniedException("Только владелец может отзывать доступ"))
                .when(locationSharingService).revokeAccess(CURRENT_USER_ID, 7L, 2L);

        // act + assert
        mockMvc.perform(delete("/api/v1/locations/7/members/2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    void should_return_409_when_revoking_last_owner() throws Exception {
        // arrange
        doThrow(new IllegalStateException("Нельзя удалить последнего владельца локации"))
                .when(locationSharingService).revokeAccess(CURRENT_USER_ID, 7L, CURRENT_USER_ID);

        // act + assert
        mockMvc.perform(delete("/api/v1/locations/7/members/1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    void should_return_404_when_revoking_nonexistent_member() throws Exception {
        // arrange
        doThrow(new EntityNotFoundException("У пользователя нет доступа к этой локации"))
                .when(locationSharingService).revokeAccess(CURRENT_USER_ID, 7L, 999L);

        // act + assert
        mockMvc.perform(delete("/api/v1/locations/7/members/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ========================= GET /shared-locations =========================

    @Test
    void should_return_shared_locations_where_user_is_caretaker() throws Exception {
        // arrange
        List<SharedLocationInfo> infos = List.of(
                new SharedLocationInfo(10L, "Гостиная", "🪴", 5L, AccessRole.CARETAKER, FIXED_INSTANT),
                new SharedLocationInfo(11L, "Кухня", "🍀", 6L, AccessRole.CARETAKER, FIXED_INSTANT)
        );
        when(locationSharingService.listSharedLocations(CURRENT_USER_ID)).thenReturn(infos);

        // act + assert
        mockMvc.perform(get("/api/v1/shared-locations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locations").isArray())
                .andExpect(jsonPath("$.locations.length()").value(2))
                .andExpect(jsonPath("$.locations[0].id").value(10))
                .andExpect(jsonPath("$.locations[0].name").value("Гостиная"))
                .andExpect(jsonPath("$.locations[0].emoji").value("🪴"))
                .andExpect(jsonPath("$.locations[0].ownerUserId").value(5))
                .andExpect(jsonPath("$.locations[0].role").value("CARETAKER"))
                .andExpect(jsonPath("$.locations[1].id").value(11));
    }

    @Test
    void should_return_empty_list_when_no_shared_locations() throws Exception {
        // arrange
        when(locationSharingService.listSharedLocations(CURRENT_USER_ID)).thenReturn(List.of());

        // act + assert
        mockMvc.perform(get("/api/v1/shared-locations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locations").isArray())
                .andExpect(jsonPath("$.locations.length()").value(0));
    }
}

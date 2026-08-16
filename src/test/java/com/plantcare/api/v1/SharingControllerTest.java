package com.plantcare.api.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.SharingInvite;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.SharingStatus;
import com.plantcare.core.service.SharingService;
import com.plantcare.core.service.UserService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} для {@link SharingController} — list/create, валидация (400),
 * cross-user/неизвестное растение (404) для API совместного ухода (issue #191).
 *
 * <p>Фильтры выключены, {@link ApiExceptionHandler} импортируется,
 * {@link CurrentUserProvider} застаблен на user id = 1.
 */
@WebMvcTest(SharingController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class SharingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SharingService sharingService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void stubCurrentUser() {
        when(currentUserProvider.currentUserId()).thenReturn(1L);
    }

    private SharingInvite mockInvite(long id, String contact, SharingStatus status,
                                     boolean canLogCare, Set<Long> plantIds) {
        SharingInvite invite = mock(SharingInvite.class);
        when(invite.getId()).thenReturn(id);
        when(invite.getInviteeContact()).thenReturn(contact);
        when(invite.getStatus()).thenReturn(status);
        when(invite.isCanLogCare()).thenReturn(canLogCare);
        when(invite.getPlantIds()).thenReturn(plantIds);
        return invite;
    }

    // ------------------------------------------------------------------ GET list

    @Test
    void should_return_members_wrapped_under_members_when_listing() throws Exception {
        SharingInvite anna = mockInvite(5L, "@anna", SharingStatus.PENDING, true, Set.of(23L, 24L));
        SharingInvite bob = mockInvite(6L, "@bob", SharingStatus.ACCEPTED, false, Set.of(23L));

        when(sharingService.listMembers(1L)).thenReturn(List.of(anna, bob));

        mockMvc.perform(get("/api/v1/sharing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members").isArray())
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[0].id").value(5))
                .andExpect(jsonPath("$.members[0].contact").value("@anna"))
                .andExpect(jsonPath("$.members[0].status").value("PENDING"))
                .andExpect(jsonPath("$.members[0].canLogCare").value(true))
                .andExpect(jsonPath("$.members[0].plantIds").isArray())
                .andExpect(jsonPath("$.members[0].plantIds.length()").value(2))
                .andExpect(jsonPath("$.members[1].id").value(6))
                .andExpect(jsonPath("$.members[1].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.members[1].canLogCare").value(false));
    }

    @Test
    void should_return_empty_members_array_when_user_has_no_invites() throws Exception {
        when(sharingService.listMembers(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/sharing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members").isArray())
                .andExpect(jsonPath("$.members.length()").value(0));
    }

    // ------------------------------------------------------------------ POST invite

    @Test
    void should_create_invite_and_return_201_when_request_valid() throws Exception {
        User user = User.builder().telegramChatId(1L).build();
        SharingInvite created = mockInvite(5L, "@anna", SharingStatus.PENDING, true, Set.of(23L, 24L));

        when(userService.getByIdOrThrow(1L)).thenReturn(user);
        when(sharingService.createInvite(eq(user), eq(List.of(23L, 24L)), eq("@anna"), eq(true)))
                .thenReturn(created);

        String body = """
                {"plantIds": [23, 24], "inviteeContact": "@anna", "canLogCare": true}
                """;

        mockMvc.perform(post("/api/v1/sharing/invites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.contact").value("@anna"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.canLogCare").value(true))
                .andExpect(jsonPath("$.plantIds.length()").value(2));
    }

    @Test
    void should_default_can_log_care_false_when_flag_omitted() throws Exception {
        User user = User.builder().telegramChatId(1L).build();
        SharingInvite created = mockInvite(7L, "@anna", SharingStatus.PENDING, false, Set.of(23L));

        when(userService.getByIdOrThrow(1L)).thenReturn(user);
        when(sharingService.createInvite(eq(user), eq(List.of(23L)), eq("@anna"), eq(false)))
                .thenReturn(created);

        String body = """
                {"plantIds": [23], "inviteeContact": "@anna"}
                """;

        mockMvc.perform(post("/api/v1/sharing/invites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.canLogCare").value(false));
    }

    @Test
    void should_return_400_when_plant_ids_empty() throws Exception {
        String body = """
                {"plantIds": [], "inviteeContact": "@anna", "canLogCare": true}
                """;

        mockMvc.perform(post("/api/v1/sharing/invites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_return_400_when_contact_blank() throws Exception {
        String body = """
                {"plantIds": [23], "inviteeContact": "", "canLogCare": true}
                """;

        mockMvc.perform(post("/api/v1/sharing/invites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_return_400_when_contact_missing() throws Exception {
        String body = """
                {"plantIds": [23]}
                """;

        mockMvc.perform(post("/api/v1/sharing/invites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_return_404_when_a_plant_is_not_owned_or_missing() throws Exception {
        User user = User.builder().telegramChatId(1L).build();
        when(userService.getByIdOrThrow(1L)).thenReturn(user);
        when(sharingService.createInvite(any(), any(), anyString(), anyBoolean()))
                .thenThrow(new EntityNotFoundException(SharingService.PLANTS_NOT_FOUND));

        String body = """
                {"plantIds": [23, 999], "inviteeContact": "@anna", "canLogCare": true}
                """;

        mockMvc.perform(post("/api/v1/sharing/invites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}

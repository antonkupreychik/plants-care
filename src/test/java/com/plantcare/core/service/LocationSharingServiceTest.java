package com.plantcare.core.service;

import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.LocationAccess;
import com.plantcare.core.domain.LocationInvite;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.AccessRole;
import com.plantcare.core.repository.LocationAccessRepository;
import com.plantcare.core.repository.LocationInviteRepository;
import com.plantcare.core.repository.LocationRepository;
import com.plantcare.core.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные тесты {@link LocationSharingService} на реальном Postgres
 * (Testcontainers, issue #77). Репозитории не мокаются.
 *
 * <p>Покрытие: генерация одноразового приглашения, приём (happy), идемпотентность
 * повторного приёма, просроченная/использованная ссылка, приглашение себя,
 * сбор subject/caretaker-id для веерной рассылки шедулера.
 */
class LocationSharingServiceTest extends IntegrationTestBase {

    @Autowired private LocationSharingService sharingService;
    @Autowired private LocationInviteRepository inviteRepository;
    @Autowired private LocationAccessRepository accessRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private UserRepository userRepository;

    private User owner;
    private User invitee;
    private Location livingRoom;

    @BeforeEach
    void setUp() {
        owner = savedUser(7701L);
        invitee = savedUser(7702L);
        livingRoom = savedLocation(owner, "Гостиная", "🛋");
    }

    @AfterEach
    void cleanup() {
        accessRepository.deleteAll();
        inviteRepository.deleteAll();
        locationRepository.deleteAll();
        userRepository.deleteAll();
    }

    // --- AC2: одноразовый deep link ---

    @Test
    void should_create_one_time_invite_with_token_and_expiry() {
        LocationInvite invite = sharingService.createInvite(owner.getId(), livingRoom.getId());

        assertThat(invite.getId()).isNotNull();
        assertThat(invite.getToken()).isNotBlank();
        assertThat(invite.getRole()).isEqualTo(AccessRole.CARETAKER);
        assertThat(invite.getExpiresAt()).isAfter(Instant.now());
        assertThat(invite.isAccepted()).isFalse();
    }

    @Test
    void should_reject_invite_for_foreign_location() {
        Location ownersOtherUser = savedLocation(invitee, "Чужая комната", "🚪");

        assertThatThrownBy(() ->
                sharingService.createInvite(owner.getId(), ownersOtherUser.getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- AC3/AC4: приём приглашения создаёт доступ ---

    @Test
    void should_grant_access_when_invitee_accepts() {
        LocationInvite invite = sharingService.createInvite(owner.getId(), livingRoom.getId());

        LocationSharingService.AcceptResult result =
                sharingService.acceptInvite(invite.getToken(), invitee);

        assertThat(result.isAccepted()).isTrue();
        assertThat(result.locationName()).contains("Гостиная");

        List<LocationAccess> access = accessRepository.findAllByObjectId(livingRoom.getId());
        assertThat(access).hasSize(1);
        assertThat(access.get(0).getSubject().getId()).isEqualTo(invitee.getId());
        assertThat(access.get(0).getRole()).isEqualTo(AccessRole.CARETAKER);

        assertThat(inviteRepository.findById(invite.getId()).orElseThrow().isAccepted()).isTrue();
    }

    @Test
    void should_be_idempotent_when_invitee_accepts_twice_via_two_invites() {
        LocationInvite first = sharingService.createInvite(owner.getId(), livingRoom.getId());
        sharingService.acceptInvite(first.getToken(), invitee);

        LocationInvite second = sharingService.createInvite(owner.getId(), livingRoom.getId());
        LocationSharingService.AcceptResult result =
                sharingService.acceptInvite(second.getToken(), invitee);

        assertThat(result.isAccepted()).isTrue();
        // Доступ не задвоился despite двух принятых приглашений.
        assertThat(accessRepository.findAllByObjectId(livingRoom.getId())).hasSize(1);
    }

    @Test
    void should_reject_reused_token() {
        LocationInvite invite = sharingService.createInvite(owner.getId(), livingRoom.getId());
        sharingService.acceptInvite(invite.getToken(), invitee);

        User third = savedUser(7703L);
        LocationSharingService.AcceptResult result =
                sharingService.acceptInvite(invite.getToken(), third);

        assertThat(result.status()).isEqualTo(LocationSharingService.AcceptResult.Status.ALREADY_USED);
        // Третий доступ не выдан — токен одноразовый.
        assertThat(accessRepository.findAllByObjectId(livingRoom.getId())).hasSize(1);
    }

    @Test
    void should_reject_expired_token() {
        LocationInvite invite = sharingService.createInvite(owner.getId(), livingRoom.getId());
        invite.setExpiresAt(Instant.now().minusSeconds(60));
        inviteRepository.save(invite);

        LocationSharingService.AcceptResult result =
                sharingService.acceptInvite(invite.getToken(), invitee);

        assertThat(result.status()).isEqualTo(LocationSharingService.AcceptResult.Status.EXPIRED);
        assertThat(accessRepository.count()).isZero();
    }

    @Test
    void should_report_not_found_for_unknown_token() {
        LocationSharingService.AcceptResult result =
                sharingService.acceptInvite("definitely-not-a-real-token", invitee);

        assertThat(result.status()).isEqualTo(LocationSharingService.AcceptResult.Status.NOT_FOUND);
    }

    @Test
    void should_report_own_invite_when_owner_accepts_own_link() {
        LocationInvite invite = sharingService.createInvite(owner.getId(), livingRoom.getId());

        LocationSharingService.AcceptResult result =
                sharingService.acceptInvite(invite.getToken(), owner);

        assertThat(result.status()).isEqualTo(LocationSharingService.AcceptResult.Status.OWN_INVITE);
        assertThat(accessRepository.count()).isZero();
    }

    // --- AC4: subject/caretaker fan-out source ---

    @Test
    void should_collect_subject_ids_and_caretaker_chat_ids_for_location() {
        LocationInvite invite = sharingService.createInvite(owner.getId(), livingRoom.getId());
        sharingService.acceptInvite(invite.getToken(), invitee);

        assertThat(sharingService.subjectUserIdsForLocation(livingRoom.getId()))
                .containsExactly(invitee.getId());
        assertThat(sharingService.caretakerChatIdsForLocation(livingRoom.getId()))
                .containsExactly(invitee.getTelegramChatId());
    }

    @Test
    void should_exclude_blocked_caretaker_from_chat_id_fanout() {
        LocationInvite invite = sharingService.createInvite(owner.getId(), livingRoom.getId());
        sharingService.acceptInvite(invite.getToken(), invitee);

        invitee.setBlocked(true);
        userRepository.save(invitee);

        assertThat(sharingService.caretakerChatIdsForLocation(livingRoom.getId())).isEmpty();
        // subject-список (без фильтра по blocked) при этом непустой.
        assertThat(sharingService.subjectUserIdsForLocation(livingRoom.getId())).hasSize(1);
    }

    @Test
    void should_return_empty_fanout_for_location_without_caretakers() {
        assertThat(sharingService.subjectUserIdsForLocation(livingRoom.getId())).isEmpty();
        assertThat(sharingService.caretakerChatIdsForLocation(null)).isEmpty();
    }

    // --- helpers ---

    private User savedUser(Long chatId) {
        return userRepository.save(User.builder()
                .telegramChatId(chatId)
                .timezone("UTC")
                .blocked(false)
                .build());
    }

    private Location savedLocation(User user, String name, String emoji) {
        return locationRepository.save(Location.builder()
                .user(user)
                .name(name)
                .emoji(emoji)
                .defaultLocation(false)
                .build());
    }
}

package com.plantcare.core.service;

import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.SharingInvite;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.SharingStatus;
import com.plantcare.core.repository.LocationRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.SharingInviteRepository;
import com.plantcare.core.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные тесты {@link SharingService} на реальном Postgres (Testcontainers, issue #191).
 *
 * <p>Репозиторий не мокается — проверяем персистентность приглашения, скоупинг растений
 * по владельцу (чужое/архивное/несуществующее растение → 404), статус PENDING,
 * нормализацию контакта и валидацию пустого ввода (400).
 */
class SharingServiceTest extends IntegrationTestBase {

    @Autowired private SharingService sharingService;
    @Autowired private SharingInviteRepository sharingInviteRepository;
    @Autowired private PlantRepository plantRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private UserRepository userRepository;

    private User owner;
    private User other;
    private Plant plant1;
    private Plant plant2;

    @BeforeEach
    void setUp() {
        owner = savedUser(9101L);
        other = savedUser(9102L);
        plant1 = savedPlant(owner, "Фикус");
        plant2 = savedPlant(owner, "Монстера");
    }

    @AfterEach
    void cleanup() {
        sharingInviteRepository.deleteAll();
        plantRepository.deleteAll();
        locationRepository.deleteAll();
        userRepository.deleteAll();
    }

    // --- AC: create invite happy path ---

    @Test
    void should_create_pending_invite_with_plants_and_can_log_care() {
        SharingInvite invite = sharingService.createInvite(
                owner, List.of(plant1.getId(), plant2.getId()), "@anna", true);

        assertThat(invite.getId()).isNotNull();

        // plantIds — LAZY-коллекция; читаем через fetch-запрос сервиса (как делает контроллер),
        // а не у detached-сущности, чтобы не упереться в no-Session.
        SharingInvite reloaded = sharingService.listMembers(owner.getId()).get(0);
        assertThat(reloaded.getInviter().getId()).isEqualTo(owner.getId());
        assertThat(reloaded.getInviteeContact()).isEqualTo("@anna");
        assertThat(reloaded.getStatus()).isEqualTo(SharingStatus.PENDING);
        assertThat(reloaded.isCanLogCare()).isTrue();
        assertThat(reloaded.getPlantIds()).containsExactlyInAnyOrder(plant1.getId(), plant2.getId());
    }

    @Test
    void should_default_can_log_care_false_when_not_granted() {
        SharingInvite invite = sharingService.createInvite(
                owner, List.of(plant1.getId()), "+79991234567", false);

        assertThat(sharingInviteRepository.findById(invite.getId()).orElseThrow().isCanLogCare())
                .isFalse();
    }

    @Test
    void should_trim_surrounding_whitespace_in_contact() {
        SharingInvite invite = sharingService.createInvite(
                owner, List.of(plant1.getId()), "   @anna   ", true);

        assertThat(sharingInviteRepository.findById(invite.getId()).orElseThrow().getInviteeContact())
                .isEqualTo("@anna");
    }

    @Test
    void should_deduplicate_repeated_plant_ids() {
        sharingService.createInvite(
                owner, List.of(plant1.getId(), plant1.getId()), "@anna", true);

        assertThat(sharingService.listMembers(owner.getId()).get(0).getPlantIds())
                .containsExactly(plant1.getId());
    }

    // --- AC: validation (400-path) ---

    @Test
    void should_reject_blank_contact() {
        assertThatThrownBy(() -> sharingService.createInvite(owner, List.of(plant1.getId()), "   ", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Контакт");

        assertThat(sharingInviteRepository.count()).isZero();
    }

    @Test
    void should_reject_null_contact() {
        assertThatThrownBy(() -> sharingService.createInvite(owner, List.of(plant1.getId()), null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_reject_empty_plant_set() {
        assertThatThrownBy(() -> sharingService.createInvite(owner, List.of(), "@anna", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("хотя бы одно растение");

        assertThat(sharingInviteRepository.count()).isZero();
    }

    // --- AC: access model — plants must belong to the owner (404-path) ---

    @Test
    void should_reject_when_a_plant_belongs_to_another_user() {
        Plant otherPlant = savedPlant(other, "Чужой кактус");

        assertThatThrownBy(() -> sharingService.createInvite(
                owner, List.of(plant1.getId(), otherPlant.getId()), "@anna", true))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage(SharingService.PLANTS_NOT_FOUND);

        assertThat(sharingInviteRepository.count()).isZero();
    }

    @Test
    void should_reject_when_a_plant_does_not_exist() {
        assertThatThrownBy(() -> sharingService.createInvite(
                owner, List.of(plant1.getId(), 999_999L), "@anna", true))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage(SharingService.PLANTS_NOT_FOUND);
    }

    @Test
    void should_reject_when_a_plant_is_archived() {
        plant2.setArchivedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        plantRepository.save(plant2);

        assertThatThrownBy(() -> sharingService.createInvite(
                owner, List.of(plant1.getId(), plant2.getId()), "@anna", true))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage(SharingService.PLANTS_NOT_FOUND);
    }

    // --- AC: list members ---

    @Test
    void should_list_invites_of_owner_with_their_plants() {
        sharingService.createInvite(owner, List.of(plant1.getId(), plant2.getId()), "@anna", true);
        sharingService.createInvite(owner, List.of(plant1.getId()), "@bob", false);

        List<SharingInvite> members = sharingService.listMembers(owner.getId());

        assertThat(members).hasSize(2);
        assertThat(members).extracting(SharingInvite::getInviteeContact)
                .containsExactlyInAnyOrder("@anna", "@bob");
        assertThat(members).allSatisfy(m ->
                assertThat(m.getStatus()).isEqualTo(SharingStatus.PENDING));
    }

    @Test
    void should_not_return_invites_of_other_users() {
        Plant otherPlant = savedPlant(other, "Чужой кактус");
        sharingService.createInvite(owner, List.of(plant1.getId()), "@anna", true);
        sharingService.createInvite(other, List.of(otherPlant.getId()), "@carol", true);

        List<SharingInvite> members = sharingService.listMembers(owner.getId());

        assertThat(members).hasSize(1);
        assertThat(members.get(0).getInviteeContact()).isEqualTo("@anna");
    }

    @Test
    void should_return_empty_list_when_owner_has_no_invites() {
        assertThat(sharingService.listMembers(owner.getId())).isEmpty();
    }

    // --- helpers ---

    private User savedUser(Long chatId) {
        return userRepository.save(User.builder()
                .telegramChatId(chatId)
                .timezone("UTC")
                .blocked(false)
                .build());
    }

    private Location savedDefaultLocation(User user) {
        return locationRepository.findByUserIdAndDefaultLocationTrue(user.getId())
                .orElseGet(() -> locationRepository.save(Location.builder()
                        .user(user)
                        .name(Location.DEFAULT_NAME)
                        .emoji(Location.DEFAULT_EMOJI)
                        .defaultLocation(true)
                        .build()));
    }

    private Plant savedPlant(User user, String name) {
        return plantRepository.save(Plant.builder()
                .user(user)
                .location(savedDefaultLocation(user))
                .name(name)
                .acquiredAt(LocalDate.of(2024, 1, 1))
                .build());
    }
}

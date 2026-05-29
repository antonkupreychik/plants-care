package com.plantcare.core.repository;

import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Notification;
import com.plantcare.core.domain.NotificationType;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты {@link NotificationRepository} на реальном Postgres (Testcontainers,
 * issue #183). Репозиторий не мокается: проверяем фактический DESC-порядок ленты,
 * работу {@link OffsetLimitPageable} с произвольным (не кратным limit) offset,
 * user-scoping счётчика непрочитанных и user-scoping point-lookup.
 *
 * <p>{@code createdAt} проставляется {@code @CreationTimestamp} на insert и может совпасть
 * в один тик, поэтому для воспроизводимого порядка задаём его детерминированно нативным
 * UPDATE (поле {@code updatable=false} для JPA) — как в {@code ShoppingListServiceTest}.
 */
class NotificationRepositoryTest extends IntegrationTestBase {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    @Autowired private NotificationRepository notificationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private PlantRepository plantRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    @PersistenceContext private EntityManager entityManager;

    private User userA;
    private User userB;

    @BeforeEach
    void setUp() {
        userA = userRepository.save(User.builder().telegramChatId(8001L).build());
        userB = userRepository.save(User.builder().telegramChatId(8002L).build());
    }

    @AfterEach
    void cleanup() {
        notificationRepository.deleteAll();
        plantRepository.deleteAll();
        locationRepository.deleteAll();
        userRepository.deleteAll();
    }

    // --- AC 1: DESC ordering ---

    @Test
    void should_return_notifications_newest_first_when_listing() {
        save(userA, NotificationType.CARE, "Старое", null, LocalDateTime.of(2026, 5, 20, 9, 0));
        save(userA, NotificationType.ALERT, "Среднее", null, LocalDateTime.of(2026, 5, 22, 9, 0));
        save(userA, NotificationType.AWARD, "Новое", null, LocalDateTime.of(2026, 5, 24, 9, 0));

        List<Notification> page = notificationRepository.findAllByUserIdOrderByCreatedAtDesc(
                userA.getId(), OffsetLimitPageable.of(0, 10, NEWEST_FIRST));

        assertThat(page)
                .extracting(Notification::getTitle)
                .containsExactly("Новое", "Среднее", "Старое");
    }

    // --- AC 1: offset/limit pagination, incl. non-multiple-of-limit offset ---

    @Test
    void should_respect_offset_and_limit_with_non_multiple_offset() {
        // Лента (DESC): [N5, N4, N3, N2, N1]. offset=1, limit=2 — окно НЕ кратно limit,
        // что доказывает работу OffsetLimitPageable (PageRequest такой offset не умеет).
        save(userA, NotificationType.CARE, "N1", null, LocalDateTime.of(2026, 5, 20, 9, 0));
        save(userA, NotificationType.CARE, "N2", null, LocalDateTime.of(2026, 5, 21, 9, 0));
        save(userA, NotificationType.CARE, "N3", null, LocalDateTime.of(2026, 5, 22, 9, 0));
        save(userA, NotificationType.CARE, "N4", null, LocalDateTime.of(2026, 5, 23, 9, 0));
        save(userA, NotificationType.CARE, "N5", null, LocalDateTime.of(2026, 5, 24, 9, 0));

        List<Notification> page = notificationRepository.findAllByUserIdOrderByCreatedAtDesc(
                userA.getId(), OffsetLimitPageable.of(1, 2, NEWEST_FIRST));

        // пропустили N5 (offset=1), взяли 2 → N4, N3
        assertThat(page)
                .extracting(Notification::getTitle)
                .containsExactly("N4", "N3");
    }

    @Test
    void should_return_empty_page_when_offset_past_end() {
        save(userA, NotificationType.CARE, "N1", null, LocalDateTime.of(2026, 5, 20, 9, 0));
        save(userA, NotificationType.CARE, "N2", null, LocalDateTime.of(2026, 5, 21, 9, 0));

        List<Notification> page = notificationRepository.findAllByUserIdOrderByCreatedAtDesc(
                userA.getId(), OffsetLimitPageable.of(5, 10, NEWEST_FIRST));

        assertThat(page).isEmpty();
    }

    @Test
    void should_not_return_other_users_notifications_in_feed() {
        save(userA, NotificationType.CARE, "Принадлежит A", null, LocalDateTime.of(2026, 5, 20, 9, 0));
        save(userB, NotificationType.CARE, "Принадлежит B", null, LocalDateTime.of(2026, 5, 21, 9, 0));

        List<Notification> page = notificationRepository.findAllByUserIdOrderByCreatedAtDesc(
                userA.getId(), OffsetLimitPageable.of(0, 10, NEWEST_FIRST));

        assertThat(page)
                .extracting(Notification::getTitle)
                .containsExactly("Принадлежит A");
    }

    // --- AC 1: unreadCount counts only unread, user-scoped ---

    @Test
    void should_count_only_unread_notifications() {
        Notification read = save(userA, NotificationType.CARE, "Прочитано", null, LocalDateTime.of(2026, 5, 20, 9, 0));
        save(userA, NotificationType.ALERT, "Непрочитано 1", null, LocalDateTime.of(2026, 5, 21, 9, 0));
        save(userA, NotificationType.ALERT, "Непрочитано 2", null, LocalDateTime.of(2026, 5, 22, 9, 0));
        markReadInDb(read.getId(), LocalDateTime.of(2026, 5, 20, 10, 0));

        long count = notificationRepository.countByUserIdAndReadAtIsNull(userA.getId());

        assertThat(count).isEqualTo(2);
    }

    @Test
    void should_not_count_other_users_unread_in_unread_count() {
        save(userA, NotificationType.CARE, "A непрочитано", null, LocalDateTime.of(2026, 5, 20, 9, 0));
        save(userB, NotificationType.CARE, "B непрочитано 1", null, LocalDateTime.of(2026, 5, 20, 9, 0));
        save(userB, NotificationType.CARE, "B непрочитано 2", null, LocalDateTime.of(2026, 5, 21, 9, 0));

        long count = notificationRepository.countByUserIdAndReadAtIsNull(userA.getId());

        assertThat(count).isEqualTo(1);
    }

    @Test
    void should_return_zero_unread_when_user_has_no_notifications() {
        long count = notificationRepository.countByUserIdAndReadAtIsNull(userA.getId());

        assertThat(count).isZero();
    }

    // --- AC 4: findByUserIdAndId is user-scoped ---

    @Test
    void should_find_own_notification_by_id() {
        Notification own = save(userA, NotificationType.CARE, "Моё", null, LocalDateTime.of(2026, 5, 20, 9, 0));

        Optional<Notification> found = notificationRepository.findByUserIdAndId(userA.getId(), own.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("Моё");
    }

    @Test
    void should_return_empty_when_looking_up_another_users_notification_by_id() {
        Notification ofB = save(userB, NotificationType.CARE, "Чужое", null, LocalDateTime.of(2026, 5, 20, 9, 0));

        // тот же id, но скоуп по userA → запись «не существует» для A
        Optional<Notification> found = notificationRepository.findByUserIdAndId(userA.getId(), ofB.getId());

        assertThat(found).isEmpty();
    }

    @Test
    void should_return_empty_when_looking_up_unknown_id() {
        Optional<Notification> found = notificationRepository.findByUserIdAndId(userA.getId(), 999_999L);

        assertThat(found).isEmpty();
    }

    // --- plant EntityGraph: plant подтягивается, deep-link читается вне транзакции ---

    @Test
    void should_eagerly_load_plant_via_entity_graph_in_feed() {
        Plant plant = savePlant(userA, "Монстера");
        save(userA, NotificationType.CARE, "С растением", plant, LocalDateTime.of(2026, 5, 20, 9, 0));
        // save() уже сделал commit (native UPDATE в своей транзакции) и clear() —
        // контекст пуст, LAZY plant нельзя дочитать через прокси.
        entityManager.clear();

        List<Notification> page = notificationRepository.findAllByUserIdOrderByCreatedAtDesc(
                userA.getId(), OffsetLimitPageable.of(0, 10, NEWEST_FIRST));

        assertThat(page).hasSize(1);
        assertThat(page.get(0).getPlant()).isNotNull();
        assertThat(page.get(0).getPlant().getName()).isEqualTo("Монстера");
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private Notification save(User user, NotificationType type, String title, Plant plant,
                              LocalDateTime createdAt) {
        Notification saved = notificationRepository.save(
                new Notification(user, type, title, "body of " + title, plant));
        setCreatedAt(saved.getId(), createdAt);
        return saved;
    }

    private Plant savePlant(User user, String name) {
        Location location = locationRepository.save(Location.builder()
                .user(user)
                .name(Location.DEFAULT_NAME)
                .emoji(Location.DEFAULT_EMOJI)
                .defaultLocation(true)
                .build());
        return plantRepository.save(Plant.builder()
                .user(user)
                .location(location)
                .name(name)
                .build());
    }

    // Нативный UPDATE требует активной транзакции, а @SpringBootTest-тесты здесь
    // не транзакционны (намеренно — чтобы тест EntityGraph реально отвязывал контекст).
    // Поэтому каждый бэкдейт оборачиваем в собственную транзакцию через TransactionTemplate.
    private void setCreatedAt(Long id, LocalDateTime createdAt) {
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery("UPDATE notifications SET created_at = :createdAt WHERE id = :id")
                        .setParameter("createdAt", createdAt)
                        .setParameter("id", id)
                        .executeUpdate());
        entityManager.clear();
    }

    private void markReadInDb(Long id, LocalDateTime readAt) {
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery("UPDATE notifications SET read_at = :readAt WHERE id = :id")
                        .setParameter("readAt", readAt)
                        .setParameter("id", id)
                        .executeUpdate());
        entityManager.clear();
    }
}

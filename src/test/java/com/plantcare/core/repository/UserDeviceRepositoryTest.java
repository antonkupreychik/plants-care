package com.plantcare.core.repository;

import com.plantcare.core.domain.DevicePlatform;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.UserDevice;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @DataJpaTest + Testcontainers для {@link UserDeviceRepository} (issue #175).
 *
 * <p>Проверяет: уникальный констрейнт по (user_id, push_token), каскадное
 * удаление при удалении user (ON DELETE CASCADE из V40), и базовые CRUD-операции.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Testcontainers
class UserDeviceRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withReuse(true);

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private UserDeviceRepository userDeviceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    // ------------------------------------------------------------------ helpers

    private User givenUser(String username) {
        return userRepository.save(User.builder().username(username).build());
    }

    private UserDevice givenDevice(User user, DevicePlatform platform, String token) {
        return userDeviceRepository.save(
                new UserDevice(user, platform, token, Instant.parse("2026-06-01T10:00:00Z")));
    }

    // ------------------------------------------------------------------ tests

    @Test
    void should_save_and_load_device() {
        // arrange
        User user = givenUser("alice");

        // act
        UserDevice saved = givenDevice(user, DevicePlatform.ANDROID, "token-abc");

        // assert
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getPlatform()).isEqualTo(DevicePlatform.ANDROID);
        assertThat(saved.getPushToken()).isEqualTo("token-abc");
        assertThat(saved.getCreatedAt()).isEqualTo(Instant.parse("2026-06-01T10:00:00Z"));
        assertThat(saved.getLastSeenAt()).isEqualTo(Instant.parse("2026-06-01T10:00:00Z"));
    }

    @Test
    void should_find_device_by_user_id_and_push_token() {
        // arrange
        User user = givenUser("bob");
        givenDevice(user, DevicePlatform.IOS, "token-ios-123");

        // act
        Optional<UserDevice> found = userDeviceRepository.findByUserIdAndPushToken(user.getId(), "token-ios-123");
        Optional<UserDevice> notFound = userDeviceRepository.findByUserIdAndPushToken(user.getId(), "wrong-token");

        // assert
        assertThat(found).isPresent();
        assertThat(found.get().getPlatform()).isEqualTo(DevicePlatform.IOS);
        assertThat(notFound).isEmpty();
    }

    @Test
    void should_find_device_by_id_and_user_id() {
        // arrange
        User user = givenUser("carol");
        UserDevice device = givenDevice(user, DevicePlatform.ANDROID, "token-carol");

        // act
        Optional<UserDevice> ownDevice = userDeviceRepository.findByIdAndUserId(device.getId(), user.getId());
        Optional<UserDevice> foreignDevice = userDeviceRepository.findByIdAndUserId(device.getId(), 99999L);

        // assert
        assertThat(ownDevice).isPresent();
        assertThat(foreignDevice).isEmpty();
    }

    @Test
    void should_enforce_unique_constraint_on_user_push_token() {
        // arrange
        User user = givenUser("dave");
        givenDevice(user, DevicePlatform.ANDROID, "duplicate-token");
        entityManager.flush();

        // act + assert — дублирующий токен для того же пользователя нарушает UNIQUE
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("""
                    INSERT INTO user_devices (user_id, platform, push_token, created_at, last_seen_at)
                    VALUES (:uid, 'ANDROID', 'duplicate-token', now(), now())
                    """)
                    .setParameter("uid", user.getId())
                    .executeUpdate();
            entityManager.flush();
        })
                .isInstanceOf(PersistenceException.class)
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .rootCause()
                .hasMessageContaining("uq_user_devices_user_push_token");
    }

    @Test
    void should_cascade_delete_devices_when_user_is_deleted() {
        // arrange
        User user = givenUser("eve");
        givenDevice(user, DevicePlatform.IOS, "token-iphone");
        givenDevice(user, DevicePlatform.ANDROID, "token-android");
        Long userId = user.getId();
        entityManager.flush();
        entityManager.clear();

        assertThat(userDeviceRepository.findByUserIdAndPushToken(userId, "token-iphone")).isPresent();

        // act — удаляем user, ожидаем CASCADE на user_devices
        userRepository.deleteById(userId);
        entityManager.flush();
        entityManager.clear();

        // assert — оба устройства удалены
        Number remaining = (Number) entityManager.createNativeQuery(
                        "SELECT count(*) FROM user_devices WHERE user_id = :uid")
                .setParameter("uid", userId)
                .getSingleResult();
        assertThat(remaining.longValue()).isZero();
    }

    @Test
    void should_update_last_seen_at_via_touch() {
        // arrange
        User user = givenUser("frank");
        UserDevice device = givenDevice(user, DevicePlatform.ANDROID, "token-frank");
        Instant laterTime = Instant.parse("2026-06-04T08:00:00Z");

        // act
        device.touch(laterTime);
        userDeviceRepository.save(device);
        entityManager.flush();
        entityManager.clear();

        // assert
        UserDevice reloaded = userDeviceRepository.findById(device.getId()).orElseThrow();
        assertThat(reloaded.getLastSeenAt()).isEqualTo(laterTime);
        // createdAt не изменился
        assertThat(reloaded.getCreatedAt()).isEqualTo(Instant.parse("2026-06-01T10:00:00Z"));
    }
}

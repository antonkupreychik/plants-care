package com.plantcare.core.repository;

import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.domain.DigestTaskItem;
import com.plantcare.core.domain.NotificationDigest;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Регрессия на прод-падение шедулера дайджестов: hypersistence-utils делает deep-copy
 * значения JSONB-атрибута через Java-сериализацию, и несериализуемый {@link DigestTaskItem}
 * ронял {@code notificationDigestRepository.save()} c {@code NonSerializableObjectException}
 * (JpaSystemException), помечая транзакцию тика rollback-only.
 */
class NotificationDigestRepositoryTest extends IntegrationTestBase {

    @Autowired private NotificationDigestRepository notificationDigestRepository;
    @Autowired private UserRepository userRepository;

    @PersistenceContext private EntityManager entityManager;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder().telegramChatId(9101L).build());
    }

    @AfterEach
    void cleanup() {
        notificationDigestRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void should_persist_digest_when_saving_task_items_as_jsonb() {
        List<DigestTaskItem> items = List.of(
                new DigestTaskItem(1L, 10L, "Монстера в гостиной", TaskType.MISTING,
                        LocalDateTime.of(2026, 8, 24, 9, 0)),
                new DigestTaskItem(2L, 11L, "Фикус на кухне", TaskType.WATERING,
                        LocalDateTime.of(2026, 8, 24, 9, 30)));

        NotificationDigest saved = notificationDigestRepository.save(
                NotificationDigest.builder().userId(user.getId()).plantTaskIds(items).build());

        assertThat(saved.getId()).isNotNull();
        assertThat(notificationDigestRepository.findById(saved.getId()))
                .get()
                .extracting(NotificationDigest::getPlantTaskIds)
                .isEqualTo(items);
    }

    /**
     * Deep-copy JSONB-атрибута выполняется на flush (dirty-checking), а не только на insert —
     * именно этот путь падал в проде.
     */
    @Test
    @Transactional
    void should_not_fail_deep_copy_when_flushing_managed_digest() {
        NotificationDigest digest = notificationDigestRepository.save(
                NotificationDigest.builder()
                        .userId(user.getId())
                        .plantTaskIds(List.of(new DigestTaskItem(3L, 12L, "Драцена", TaskType.WATERING,
                                LocalDateTime.of(2026, 8, 24, 21, 12))))
                        .build());

        assertThatCode(() -> {
            digest.setPlantTaskIds(List.of(new DigestTaskItem(4L, 13L, "Калатея", TaskType.MISTING,
                    LocalDateTime.of(2026, 8, 25, 11, 46))));
            entityManager.flush();
        }).doesNotThrowAnyException();
    }
}

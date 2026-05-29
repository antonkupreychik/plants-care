package com.plantcare.core.service;

import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.domain.Notification;
import com.plantcare.core.domain.NotificationType;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.NotificationRepository;
import com.plantcare.core.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные тесты {@link NotificationService} на реальном Postgres (Testcontainers,
 * issue #183). Репозиторий не мокается — проверяем фактическую персистентность, скоупинг
 * по владельцу, идемпотентность {@code markRead} и UTC-семантику времени прочтения.
 *
 * <p>{@link Clock} подменяется на {@link MutableClock} (@Primary) — фиксированный
 * instant, который можно «двигать» между вызовами, чтобы доказать идемпотентность
 * {@code markRead}. Зона Clock — UTC, как в проде ({@code ClockConfig = systemUTC()}):
 * сервис считает {@code LocalDateTime.now(clock)}, и контракт проекта — хранить UTC
 * wall-clock. Время прочтения берётся ИЗ инжектированного Clock, а не из
 * {@code LocalDateTime.now()} с дефолтной зоной JVM, поэтому значение не зависит от
 * того, в какой таймзоне запущен сервер (Asia/Almaty, Europe/Moscow и т.п.).
 */
@Import(NotificationServiceTest.FixedClockConfig.class)
class NotificationServiceTest extends IntegrationTestBase {

    /** Момент «сейчас» по умолчанию для всех тестов: 2026-05-22T10:00:00Z. */
    private static final Instant FIXED_NOW = Instant.parse("2026-05-22T10:00:00Z");

    @Autowired private NotificationService notificationService;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MutableClock clock;

    private User userA;
    private User userB;

    @BeforeEach
    void setUp() {
        clock.set(FIXED_NOW);
        userA = userRepository.save(User.builder().telegramChatId(9001L).build());
        userB = userRepository.save(User.builder().telegramChatId(9002L).build());
    }

    @AfterEach
    void cleanup() {
        notificationRepository.deleteAll();
        userRepository.deleteAll();
    }

    // --- AC 1: happy — create + list + unreadCount ---

    @Test
    void should_create_list_and_count_unread_when_notifications_added() {
        notificationService.create(userA, NotificationType.CARE, "Полить монстеру", "Пора полить", null);
        notificationService.create(userA, NotificationType.ALERT, "Низкая влажность", "Опрыскай", null);

        List<Notification> feed = notificationService.list(userA.getId(), 0, 20);
        long unread = notificationService.unreadCount(userA.getId());

        assertThat(feed)
                .extracting(Notification::getTitle)
                .containsExactlyInAnyOrder("Полить монстеру", "Низкая влажность");
        assertThat(unread).isEqualTo(2);
    }

    @Test
    void should_return_empty_feed_and_zero_unread_when_user_has_no_notifications() {
        List<Notification> feed = notificationService.list(userA.getId(), 0, 20);

        assertThat(feed).isEmpty();
        assertThat(notificationService.unreadCount(userA.getId())).isZero();
    }

    @Test
    void should_decrease_unread_count_after_marking_read() {
        Notification n = notificationService.create(userA, NotificationType.CARE, "T", "B", null);
        assertThat(notificationService.unreadCount(userA.getId())).isEqualTo(1);

        notificationService.markRead(userA.getId(), n.getId());

        assertThat(notificationService.unreadCount(userA.getId())).isZero();
    }

    // --- AC 2: idempotency (CRITICAL) — second markRead must NOT move readAt ---

    @Test
    void should_keep_first_read_at_when_mark_read_called_twice() {
        Notification n = notificationService.create(userA, NotificationType.CARE, "T", "B", null);
        LocalDateTime expectedFirstReadAt = LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);

        // первый markRead на FIXED_NOW
        notificationService.markRead(userA.getId(), n.getId());
        LocalDateTime afterFirst =
                notificationRepository.findById(n.getId()).orElseThrow().getReadAt();

        // переводим часы на час вперёд и повторяем — идемпотентность: момент не должен сдвинуться
        clock.set(FIXED_NOW.plusSeconds(3600));
        notificationService.markRead(userA.getId(), n.getId());
        LocalDateTime afterSecond =
                notificationRepository.findById(n.getId()).orElseThrow().getReadAt();

        assertThat(afterFirst).isEqualTo(expectedFirstReadAt);
        assertThat(afterSecond).isEqualTo(expectedFirstReadAt);
    }

    @Test
    void should_not_increase_unread_count_back_when_mark_read_called_twice() {
        Notification n = notificationService.create(userA, NotificationType.CARE, "T", "B", null);

        notificationService.markRead(userA.getId(), n.getId());
        notificationService.markRead(userA.getId(), n.getId());

        assertThat(notificationService.unreadCount(userA.getId())).isZero();
    }

    // --- AC 4: failure — unknown id / cross-user → EntityNotFoundException, no mutation ---

    @Test
    void should_throw_not_found_when_marking_unknown_id() {
        assertThatThrownBy(() -> notificationService.markRead(userA.getId(), 999_999L))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("999999");
    }

    @Test
    void should_throw_not_found_and_keep_unread_when_marking_another_users_notification() {
        Notification ofB = notificationService.create(userB, NotificationType.CARE, "T", "B", null);

        assertThatThrownBy(() -> notificationService.markRead(userA.getId(), ofB.getId()))
                .isInstanceOf(EntityNotFoundException.class);

        // чужая запись не помечена прочитанной
        Notification reloaded = notificationRepository.findById(ofB.getId()).orElseThrow();
        assertThat(reloaded.getReadAt()).isNull();
        assertThat(notificationService.unreadCount(userB.getId())).isEqualTo(1);
    }

    // --- AC 3 / CLAUDE.md timezone mandate: readAt is UTC wall-clock, zone-independent ---

    @Test
    void should_store_read_at_as_utc_wall_clock_regardless_of_jvm_default_zone() {
        // Сервис берёт время из инжектированного UTC-Clock (FIXED_NOW = 10:00Z), а НЕ из
        // LocalDateTime.now() с дефолтной зоной JVM. Чтобы это доказать, на время теста
        // подменяем дефолтную зону JVM на Asia/Almaty (UTC+5): если бы код читал системную
        // зону, в БД легло бы 15:00. Контракт проекта (UTC wall-clock) требует 10:00.
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Almaty"));
            Notification n = notificationService.create(userA, NotificationType.CARE, "T", "B", null);
            LocalDateTime expectedUtc = LocalDateTime.of(2026, 5, 22, 10, 0, 0);

            notificationService.markRead(userA.getId(), n.getId());

            LocalDateTime stored = notificationRepository.findById(n.getId()).orElseThrow().getReadAt();
            assertThat(stored).isEqualTo(expectedUtc);
            // и точно НЕ локальное время зоны Алматы (15:00)
            assertThat(stored).isNotEqualTo(LocalDateTime.of(2026, 5, 22, 15, 0, 0));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    // ====================================================================
    // test infra: подменяемый Clock в не-UTC зоне
    // ====================================================================

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            // UTC-зона как в проде (ClockConfig = systemUTC): сервис хранит UTC wall-clock.
            return new MutableClock(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    /** Clock с изменяемым instant — позволяет «двигать» время между вызовами в тесте. */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        private final ZoneId zone;

        MutableClock(Instant initial, ZoneId zone) {
            this.now = new AtomicReference<>(initial);
            this.zone = zone;
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(now.get(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}

package com.plantcare.bot.admin.scheduler.service;

import com.plantcare.bot.admin.dashboard.dto.SchedulerHealth;
import com.plantcare.bot.admin.dashboard.health.SchedulerHealthProvider;
import com.plantcare.bot.admin.scheduler.dto.SchedulerPageDto;
import com.plantcare.bot.admin.scheduler.repository.AdminSchedulerRepository;
import com.plantcare.bot.service.NotificationSchedulerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdminSchedulerService — страница /admin/scheduler (issue #59)")
class AdminSchedulerServiceTest {

    @Mock private AdminSchedulerRepository repository;
    @Mock private SchedulerHealthProvider healthProvider;
    @Mock private NotificationSchedulerService schedulerService;
    @Mock private com.plantcare.bot.repository.CareScheduleRepository careScheduleRepository;

    private AdminSchedulerService service;

    @BeforeEach
    void setUp() {
        // Реальный AdminProperties вместо мока — у него final-fields через
        // @RequiredArgsConstructor, конструктор с пятью аргументами.
        com.plantcare.bot.admin.config.AdminProperties props =
                new com.plantcare.bot.admin.config.AdminProperties(
                        "admin", "$2a$10$hash", 8,
                        new com.plantcare.bot.admin.config.AdminProperties.RateLimit(5, 60),
                        new com.plantcare.bot.admin.config.AdminProperties.Dashboard("UTC", 4)
                );
        service = new AdminSchedulerService(
                repository, healthProvider, schedulerService, careScheduleRepository, props
        );

        when(healthProvider.currentHealth()).thenReturn(SchedulerHealth.from(Instant.now()));
        when(repository.countSchedulesInQueue(any())).thenReturn(3L);
        when(repository.countSentSince(any())).thenReturn(42L);
        when(repository.sentByHourSince(any(), any())).thenReturn(new HashMap<>());
        when(repository.hourlyTimeline(anyInt(), any())).thenReturn(List.of(
                new int[]{1, 0}, new int[]{2, 0}, new int[]{3, 5}  // упрощённо
        ));
        when(careScheduleRepository.findSchedulesDueBefore(any()))
                .thenReturn(java.util.List.of());
    }

    @Nested
    @DisplayName("buildPage")
    class BuildPage {

        @Test
        @DisplayName("Возвращает health, queue, sent, errors placeholder и часовые бакеты")
        void shouldAssembleSnapshot() {
            SchedulerPageDto page = service.buildPage();

            assertThat(page.health().isHealthy()).isTrue();
            assertThat(page.schedulesInQueue()).isEqualTo(3L);
            assertThat(page.sentLast24h()).isEqualTo(42L);
            assertThat(page.errorsLast24h()).isEqualTo(-1L);
            assertThat(page.isErrorsPlaceholder()).isTrue();
            assertThat(page.sendsByHour()).hasSize(3);
            assertThat(page.triggerCooldownSecondsLeft()).isEqualTo(0);
        }

        /*@Test
        @DisplayName("Часы форматируются как «HH:00» с лидирующим нулём")
        void shouldFormatHourLabelsWithLeadingZero() {
            when(repository.hourlyTimeline(any(), any())).thenReturn(List.of(
                    new int[]{0, 1}, new int[]{9, 2}, new int[]{14, 3}, new int[]{23, 4}
            ));

            SchedulerPageDto page = service.buildPage();

            assertThat(page.sendsByHour())
                    .extracting(SchedulerPageDto.HourBucket::hourLabel)
                    .containsExactly("00:00", "09:00", "14:00", "23:00");
        }*/
    }

    @Nested
    @DisplayName("triggerTick")
    class TriggerTick {

        @Test
        @DisplayName("Первый клик вызывает scheduler, возвращает SUCCESS с count")
        void shouldRunSchedulerOnFirstClick() {
            when(schedulerService.triggerManually()).thenReturn(7);

            AdminSchedulerService.TriggerResult result = service.triggerTick();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.processed()).isEqualTo(7);
            verify(schedulerService).triggerManually();
        }

        @Test
        @DisplayName("Второй клик подряд блокируется cooldown'ом, scheduler не зовётся")
        void shouldBlockSpamClicks() {
            when(schedulerService.triggerManually()).thenReturn(1);
            service.triggerTick();  // первый — успешный

            AdminSchedulerService.TriggerResult second = service.triggerTick();

            assertThat(second.isCooldown()).isTrue();
            assertThat(second.cooldownSecondsLeft()).isBetween(1L, 30L);
            verify(schedulerService, times(1)).triggerManually();  // ровно один вызов
        }

        @Test
        @DisplayName("Падение шедулера → FAILURE и снятие cooldown'а (повторить можно сразу)")
        void shouldFailGracefullyAndAllowRetry() {
            when(schedulerService.triggerManually())
                    .thenThrow(new RuntimeException("DB unreachable"))
                    .thenReturn(2);

            AdminSchedulerService.TriggerResult fail = service.triggerTick();
            assertThat(fail.isFailure()).isTrue();
            assertThat(fail.error()).contains("DB unreachable");
            assertThat(service.cooldownSecondsLeft()).isEqualTo(0);  // cooldown снят

            AdminSchedulerService.TriggerResult retry = service.triggerTick();
            assertThat(retry.isSuccess()).isTrue();
            assertThat(retry.processed()).isEqualTo(2);
        }

        @Test
        @DisplayName("buildPage после успешного триггера показывает оставшийся cooldown в DTO")
        void shouldExposeCooldownInDto() {
            when(schedulerService.triggerManually()).thenReturn(0);
            service.triggerTick();

            SchedulerPageDto page = service.buildPage();

            assertThat(page.triggerCooldownSecondsLeft()).isGreaterThan(0);
            assertThat(page.triggerCooldownSecondsLeft())
                    .isLessThanOrEqualTo(Math.toIntExact(AdminSchedulerService.TRIGGER_COOLDOWN_SECONDS));
        }
    }

    @Nested
    @DisplayName("Идемпотентность ручного триггера")
    class Idempotency {

        @Test
        @DisplayName("Cooldown реально блокирует параллельный второй вызов "
                + "(дополнительно к notifications_log дедупу)")
        void shouldNotInvokeSchedulerTwiceForRapidCalls() {
            when(schedulerService.triggerManually()).thenReturn(5);

            service.triggerTick();
            service.triggerTick();
            service.triggerTick();
            service.triggerTick();

            verify(schedulerService, times(1)).triggerManually();
        }
    }

    @Nested
    @DisplayName("Очередь и per-schedule действия")
    class QueueAndPerScheduleActions {

        @Test
        @DisplayName("buildPage() заполняет queue из careScheduleRepository.findSchedulesDueBefore")
        void shouldFillQueueFromUpcoming() {
            var user = com.plantcare.bot.domain.User.builder()
                    .telegramChatId(100L).username("alice").build();
            var plant = com.plantcare.bot.domain.Plant.builder()
                    .user(user).name("Монстера").build();
            var s = com.plantcare.bot.domain.CareSchedule.builder()
                    .plant(plant)
                    .taskType(com.plantcare.bot.domain.enums.TaskType.WATERING)
                    .intervalDays(7)
                    .nextDueAt(java.time.LocalDateTime.now().plusHours(2))
                    .active(true)
                    .build();
            org.springframework.test.util.ReflectionTestUtils.setField(s, "id", 42L);

            when(careScheduleRepository.findSchedulesDueBefore(any()))
                    .thenReturn(java.util.List.of(s));

            SchedulerPageDto page = service.buildPage();

            assertThat(page.queue()).hasSize(1);
            var q = page.queue().get(0);
            assertThat(q.scheduleId()).isEqualTo(42L);
            assertThat(q.plantName()).isEqualTo("Монстера");
            assertThat(q.taskType()).isEqualTo("WATERING");
            assertThat(q.taskEmoji()).isEqualTo("💧");
            assertThat(q.chatId()).isEqualTo(100L);
            assertThat(q.username()).isEqualTo("alice");
            assertThat(q.intervalDays()).isEqualTo(7);
            assertThat(q.overdue()).isFalse();
            assertThat(q.dueAtHuman()).contains("через");
            assertThat(page.queueTruncated()).isFalse();
        }

        @Test
        @DisplayName("Просроченное расписание помечается overdue=true и 'X мин назад'")
        void shouldMarkOverdue() {
            var user = com.plantcare.bot.domain.User.builder().telegramChatId(1L).build();
            var plant = com.plantcare.bot.domain.Plant.builder().user(user).name("X").build();
            var s = com.plantcare.bot.domain.CareSchedule.builder()
                    .plant(plant)
                    .taskType(com.plantcare.bot.domain.enums.TaskType.MISTING)
                    .intervalDays(3)
                    .nextDueAt(java.time.LocalDateTime.now().minusMinutes(15))
                    .active(true)
                    .build();
            org.springframework.test.util.ReflectionTestUtils.setField(s, "id", 1L);

            when(careScheduleRepository.findSchedulesDueBefore(any()))
                    .thenReturn(java.util.List.of(s));

            SchedulerPageDto page = service.buildPage();

            assertThat(page.queue().get(0).overdue()).isTrue();
            assertThat(page.queue().get(0).dueAtHuman()).contains("назад");
        }

        @Test
        @DisplayName("queue усечена до QUEUE_LIMIT, флаг queueTruncated=true")
        void shouldTruncateQueueAtLimit() {
            var bigList = new java.util.ArrayList<com.plantcare.bot.domain.CareSchedule>();
            for (int i = 0; i < AdminSchedulerService.QUEUE_LIMIT + 5; i++) {
                var user = com.plantcare.bot.domain.User.builder().telegramChatId((long) i).build();
                var plant = com.plantcare.bot.domain.Plant.builder().user(user).name("P" + i).build();
                var s = com.plantcare.bot.domain.CareSchedule.builder()
                        .plant(plant)
                        .taskType(com.plantcare.bot.domain.enums.TaskType.WATERING)
                        .intervalDays(7)
                        .nextDueAt(java.time.LocalDateTime.now())
                        .active(true)
                        .build();
                org.springframework.test.util.ReflectionTestUtils.setField(s, "id", (long) i);
                bigList.add(s);
            }
            when(careScheduleRepository.findSchedulesDueBefore(any())).thenReturn(bigList);

            SchedulerPageDto page = service.buildPage();

            assertThat(page.queue()).hasSize(AdminSchedulerService.QUEUE_LIMIT);
            assertThat(page.queueTruncated()).isTrue();
        }

        @Test
        @DisplayName("sendOne(force=false) делегирует в NotificationSchedulerService с тем же флагом")
        void shouldDelegateSendOne() {
            when(schedulerService.sendOneSchedule(42L, false))
                    .thenReturn(NotificationSchedulerService.SendOneResult.sent());

            var result = service.sendOne(42L, false);

            assertThat(result.isSent()).isTrue();
            verify(schedulerService).sendOneSchedule(42L, false);
        }

        @Test
        @DisplayName("sendOne(force=true) пробрасывает force-флаг")
        void shouldDelegateSendOneWithForce() {
            when(schedulerService.sendOneSchedule(42L, true))
                    .thenReturn(NotificationSchedulerService.SendOneResult.sent());

            service.sendOne(42L, true);

            verify(schedulerService).sendOneSchedule(42L, true);
        }

        @Test
        @DisplayName("skipOne делегирует и возвращает результат")
        void shouldDelegateSkip() {
            when(schedulerService.skipOneSchedule(42L)).thenReturn(true);

            assertThat(service.skipOne(42L)).isTrue();
            verify(schedulerService).skipOneSchedule(42L);
        }
    }
}

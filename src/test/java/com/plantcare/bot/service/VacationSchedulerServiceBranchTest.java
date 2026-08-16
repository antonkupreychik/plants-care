package com.plantcare.bot.service;

import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.bot.telegram.RateLimitedTelegramSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Дополнительные branch-тесты {@link VacationSchedulerService} за пределами
 * {@link VacationSchedulerServiceTest} (интеграционного, на Testcontainers).
 * Здесь — чистый Mockito unit-тест, чтобы детерминированно бить по веткам,
 * которые в интеграционном тесте практически не воспроизвести: гонку на
 * {@code clearPausedUntilIfActive}, исключения внутри самого тика и
 * "…и ещё N" хвост списка просрочек.
 *
 * <p>{@code self()} внутри {@link VacationSchedulerService#tick()} получает бин
 * через {@link ApplicationContext#getBean}, чтобы {@code @Transactional}-методы
 * проходили через Spring-прокси в проде. В unit-тесте подменяем
 * {@code getBean(...)} на возврат того же самого экземпляра — транзакционность
 * тут не нужна, реального аспекта нет.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VacationSchedulerService — branch-покрытие (Mockito unit)")
class VacationSchedulerServiceBranchTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-05-23T10:00:00Z");
    private static final LocalDateTime FIXED_NOW =
            LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

    @Mock private UserRepository userRepository;
    @Mock private CareScheduleRepository careScheduleRepository;
    @Mock private RateLimitedTelegramSender telegramSender;
    @Mock private ApplicationContext applicationContext;

    private VacationSchedulerService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        service = new VacationSchedulerService(
                userRepository, careScheduleRepository, telegramSender, clock, applicationContext);
        when(applicationContext.getBean(VacationSchedulerService.class)).thenReturn(service);
        when(userRepository.findVacationEndingBetween(any(), any())).thenReturn(List.of());
        when(userRepository.findVacationEnded(any())).thenReturn(List.of());
    }

    @Nested
    @DisplayName("Устойчивость тика к исключениям")
    class TickResilience {

        @Test
        @DisplayName("findVacationEndingBetween бросает: welcome-back блок всё равно выполняется")
        void should_still_run_welcome_back_block_when_tomorrow_reminder_query_throws() {
            when(userRepository.findVacationEndingBetween(any(), any()))
                    .thenThrow(new RuntimeException("DB down"));

            assertThatCode(() -> service.tick()).doesNotThrowAnyException();

            verify(userRepository).findVacationEnded(any());
        }

        @Test
        @DisplayName("findVacationEnded бросает: исключение перехвачено, push не уходит")
        void should_swallow_exception_when_welcome_back_query_throws() {
            when(userRepository.findVacationEnded(any())).thenThrow(new RuntimeException("DB down"));

            assertThatCode(() -> service.tick()).doesNotThrowAnyException();

            verify(telegramSender, never()).enqueue(any(SendMessage.class));
        }
    }

    @Nested
    @DisplayName("Гонка на clearPausedUntilIfActive (issue #53)")
    class RaceCondition {

        @Test
        @DisplayName("clearPausedUntilIfActive вернул 0 (уже сброшено другим потоком) → welcome-back не шлётся")
        void should_skip_welcome_back_when_clear_returns_zero() {
            User user = user(1L, 1000L);
            when(userRepository.findVacationEnded(any())).thenReturn(List.of(user));
            when(userRepository.clearPausedUntilIfActive(1L)).thenReturn(0);

            service.tick();

            verify(telegramSender, never()).enqueue(any(SendMessage.class));
            verify(careScheduleRepository, never()).findOverdueForUser(anyLong(), any());
        }

        @Test
        @DisplayName("clearPausedUntilIfActive вернул 1 → welcome-back отправляется")
        void should_send_welcome_back_when_clear_returns_one() {
            User user = user(2L, 2000L);
            when(userRepository.findVacationEnded(any())).thenReturn(List.of(user));
            when(userRepository.clearPausedUntilIfActive(2L)).thenReturn(1);
            when(careScheduleRepository.findOverdueForUser(eq(2L), any())).thenReturn(List.of());

            service.tick();

            verify(telegramSender).enqueue(any(SendMessage.class));
        }
    }

    @Nested
    @DisplayName("Текст welcome-back: лимит списка и склонение дней")
    class WelcomeBackText {

        @Test
        @DisplayName("Больше 10 просрочек: показывает первые 10 и '…и ещё N'")
        void should_show_remaining_count_when_more_than_ten_overdue() {
            User user = user(3L, 3000L);
            when(userRepository.findVacationEnded(any())).thenReturn(List.of(user));
            when(userRepository.clearPausedUntilIfActive(3L)).thenReturn(1);

            List<CareSchedule> overdue = new java.util.ArrayList<>();
            for (int i = 0; i < 13; i++) {
                overdue.add(scheduleOverdueBy(user, 2));
            }
            when(careScheduleRepository.findOverdueForUser(eq(3L), any())).thenReturn(overdue);

            service.tick();

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender).enqueue(captor.capture());
            assertThat(captor.getValue().getText()).contains("…и ещё 3");
        }

        @Test
        @DisplayName("Просрочка < 1 дня (overdueDays=0) не показывает '(просрочено ...)'")
        void should_not_show_overdue_suffix_when_less_than_one_day_overdue() {
            User user = user(4L, 4000L);
            when(userRepository.findVacationEnded(any())).thenReturn(List.of(user));
            when(userRepository.clearPausedUntilIfActive(4L)).thenReturn(1);

            CareSchedule barelyOverdue = CareSchedule.builder()
                    .plant(plantOf(user, "Кактус"))
                    .taskType(TaskType.WATERING)
                    .intervalDays(3)
                    .nextDueAt(FIXED_NOW.minusHours(2))
                    .active(true)
                    .build();
            when(careScheduleRepository.findOverdueForUser(eq(4L), any()))
                    .thenReturn(List.of(barelyOverdue));

            service.tick();

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender).enqueue(captor.capture());
            assertThat(captor.getValue().getText()).doesNotContain("просрочено");
            assertThat(captor.getValue().getText()).contains("Кактус");
        }

        @Test
        @DisplayName("Склонение 'дней' на границе 11-14 (исключение из общего mod10 правила)")
        void should_pluralize_days_correctly_at_eleven_to_fourteen_boundary() {
            User user = user(5L, 5000L);
            when(userRepository.findVacationEnded(any())).thenReturn(List.of(user));
            when(userRepository.clearPausedUntilIfActive(5L)).thenReturn(1);

            CareSchedule overdue11 = scheduleOverdueBy(user, 11);
            when(careScheduleRepository.findOverdueForUser(eq(5L), any())).thenReturn(List.of(overdue11));

            service.tick();

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender).enqueue(captor.capture());
            // mod10==1 но mod100==11 — попадает в исключение, должно быть "11 дней", не "11 день".
            assertThat(captor.getValue().getText()).contains("просрочено 11 дней");
        }
    }

    // ===== timezone sanity (VacationSchedulerService не TZ-специфичен per-user, но
    // расчёт окна не должен зависеть от TZ бина Clock — санити-проверка с Europe/Moscow) =====

    @Test
    @DisplayName("Окно tomorrow-back считается одинаково независимо от TZ бина Clock (санити, Europe/Moscow)")
    void should_compute_same_window_regardless_of_clock_zone_europe_moscow() {
        Clock moscowClock = Clock.fixed(Instant.parse("2026-05-23T10:00:00Z"), ZoneId.of("Europe/Moscow"));
        VacationSchedulerService moscowService = new VacationSchedulerService(
                userRepository, careScheduleRepository, telegramSender, moscowClock, applicationContext);
        when(applicationContext.getBean(VacationSchedulerService.class)).thenReturn(moscowService);

        User user = user(6L, 6000L);
        LocalDateTime now = LocalDateTime.now(moscowClock);
        user.setPausedUntil(now.plusHours(23).plusMinutes(30));
        when(userRepository.findVacationEndingBetween(
                eq(now.plusHours(23)), eq(now.plusHours(24)))).thenReturn(List.of(user));

        moscowService.tick();

        verify(telegramSender).enqueue(any(SendMessage.class));
    }

    // ===== helpers =====

    private User user(long id, long chatId) {
        User u = User.builder().telegramChatId(chatId).timezone("UTC").blocked(false).build();
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    private Plant plantOf(User user, String name) {
        Plant plant = Plant.builder().user(user).name(name).build();
        ReflectionTestUtils.setField(plant, "id", 999L);
        return plant;
    }

    private CareSchedule scheduleOverdueBy(User user, long days) {
        return CareSchedule.builder()
                .plant(plantOf(user, "Растение"))
                .taskType(TaskType.WATERING)
                .intervalDays(7)
                .nextDueAt(FIXED_NOW.minusDays(days).minusHours(1))
                .active(true)
                .build();
    }
}

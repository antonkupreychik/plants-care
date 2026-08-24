package com.plantcare.bot.service;

import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.PlantAcclimationService;
import com.plantcare.bot.telegram.RateLimitedTelegramSender;
import com.plantcare.bot.telegram.SendCallbacks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link AcclimationSchedulerService} (issue #75) — до этой правки
 * тестов не было вообще (baseline: 71m/7c lines, 26 непокрытых branch).
 *
 * <p>ВАЖНО (см. отчёт агента): в отличие от {@link NotificationSchedulerService}
 * и {@link PlantAnniversaryScheduler}, этот шедулер НЕ инжектирует {@link
 * java.time.Clock} — {@code tick()} берёт {@code LocalDateTime.now()}, а
 * {@code isQuietHours} — {@code Instant.now()} напрямую (нарушение TIME RULE из
 * CLAUDE.md). Это существующий production-код, который нельзя трогать в рамках
 * этой задачи. Поэтому quiet-hours здесь проверяется не точной TZ-границей на
 * фиксированном Instant (как в NotificationSchedulerServiceTest), а окном,
 * которое захватывает почти любое реальное время суток (00:00–23:59) —
 * детерминировано с точностью до одной секунды в сутки.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit-тесты для AcclimationSchedulerService (issue #75)")
class AcclimationSchedulerServiceTest {

    @Mock
    private PlantAcclimationService plantAcclimationService;

    @Mock
    private RateLimitedTelegramSender telegramSender;

    @InjectMocks
    private AcclimationSchedulerService scheduler;

    @BeforeEach
    void setUp() {
        when(plantAcclimationService.findPlantsToFinish(any())).thenReturn(List.of());
        when(plantAcclimationService.findPlantsForCheckin(any())).thenReturn(List.of());
    }

    private User activeUser(long chatId, String tz) {
        User user = User.builder()
                .telegramChatId(chatId)
                .timezone(tz)
                // Отключённые quiet-hours (start==end) — почти всегда "не тихие часы".
                .quietHoursStart(LocalTime.of(0, 0))
                .quietHoursEnd(LocalTime.of(0, 0))
                .blocked(false)
                .build();
        ReflectionTestUtils.setField(user, "id", chatId);
        return user;
    }

    private Plant plantOf(long id, String name, User user) {
        Plant plant = Plant.builder().user(user).name(name).build();
        ReflectionTestUtils.setField(plant, "id", id);
        return plant;
    }

    @Nested
    @DisplayName("Финализация акклиматизации")
    class Finish {

        @Test
        @DisplayName("Активный юзер → финиш-сообщение уходит в очередь")
        void should_enqueue_finish_message_when_user_active() {
            User user = activeUser(100L, "Europe/Minsk");
            Plant plant = plantOf(10L, "Монстера", user);
            when(plantAcclimationService.findPlantsToFinish(any())).thenReturn(List.of(plant));

            scheduler.tick();

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender).enqueue(captor.capture(), any(SendCallbacks.class));
            assertThat(captor.getValue().getChatId()).isEqualTo("100");
            assertThat(captor.getValue().getText()).contains("Акклиматизация для Монстера завершена");
        }

        @Test
        @DisplayName("onSuccess колбэк вызывает finishById только после успешной отправки")
        void should_call_finishById_only_after_successful_send() {
            User user = activeUser(100L, "Europe/Minsk");
            Plant plant = plantOf(10L, "Монстера", user);
            when(plantAcclimationService.findPlantsToFinish(any())).thenReturn(List.of(plant));

            scheduler.tick();

            ArgumentCaptor<SendCallbacks> captor = ArgumentCaptor.forClass(SendCallbacks.class);
            verify(telegramSender).enqueue(any(SendMessage.class), captor.capture());
            verify(plantAcclimationService, never()).finishById(anyLong());

            captor.getValue().onSuccess().run();

            verify(plantAcclimationService).finishById(10L);
        }

        @Test
        @DisplayName("Юзер на паузе → финиш-сообщение не отправляется")
        void should_skip_finish_when_user_paused() {
            User user = activeUser(100L, "Europe/Minsk");
            user.setPausedUntil(LocalDateTime.now().plusHours(1));
            Plant plant = plantOf(10L, "Монстера", user);
            when(plantAcclimationService.findPlantsToFinish(any())).thenReturn(List.of(plant));

            scheduler.tick();

            verifyNoInteractions(telegramSender);
        }

        @Test
        @DisplayName("Тихие часы активны (00:00-23:59) → финиш-сообщение не отправляется")
        void should_skip_finish_when_quiet_hours_active() {
            User user = User.builder()
                    .telegramChatId(100L).timezone("Europe/Minsk")
                    .quietHoursStart(LocalTime.of(0, 0)).quietHoursEnd(LocalTime.of(23, 59))
                    .blocked(false).build();
            ReflectionTestUtils.setField(user, "id", 100L);
            Plant plant = plantOf(10L, "Монстера", user);
            when(plantAcclimationService.findPlantsToFinish(any())).thenReturn(List.of(plant));

            scheduler.tick();

            verifyNoInteractions(telegramSender);
        }

        @Test
        @DisplayName("Ошибка на одном растении не прерывает обработку остальных (finish)")
        void should_continue_when_one_plant_throws_during_finish() {
            Plant broken = plantOf(10L, "Сломанное", null); // getUser() == null → NPE внутри try
            User healthyUser = activeUser(200L, "Europe/Minsk");
            Plant healthy = plantOf(20L, "Здоровое", healthyUser);
            when(plantAcclimationService.findPlantsToFinish(any())).thenReturn(List.of(broken, healthy));

            scheduler.tick();

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender, times(1)).enqueue(captor.capture(), any(SendCallbacks.class));
            assertThat(captor.getValue().getChatId()).isEqualTo("200");
        }
    }

    @Nested
    @DisplayName("Check-in вопросы")
    class Checkin {

        @Test
        @DisplayName("Активный юзер → check-in вопрос с 3 кнопками (OK/WILT/YELLOW)")
        void should_enqueue_checkin_message_with_three_buttons_when_user_active() {
            User user = activeUser(100L, "Europe/Minsk");
            Plant plant = plantOf(10L, "Монстера", user);
            when(plantAcclimationService.findPlantsForCheckin(any())).thenReturn(List.of(plant));

            scheduler.tick();

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender).enqueue(captor.capture(), any(SendCallbacks.class));
            assertThat(captor.getValue().getText()).contains("Как выглядит Монстера?");

            InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
            List<String> callbackData = keyboard.getKeyboard().stream()
                    .flatMap(Collection::stream)
                    .map(InlineKeyboardButton::getCallbackData)
                    .toList();
            assertThat(callbackData).containsExactly(
                    "v1:accl_checkin:10:OK", "v1:accl_checkin:10:WILT", "v1:accl_checkin:10:YELLOW");
        }

        @Test
        @DisplayName("onSuccess колбэк планирует следующий check-in только после успешной отправки")
        void should_schedule_next_checkin_only_after_successful_send() {
            User user = activeUser(100L, "Europe/Minsk");
            Plant plant = plantOf(10L, "Монстера", user);
            when(plantAcclimationService.findPlantsForCheckin(any())).thenReturn(List.of(plant));

            scheduler.tick();

            ArgumentCaptor<SendCallbacks> captor = ArgumentCaptor.forClass(SendCallbacks.class);
            verify(telegramSender).enqueue(any(SendMessage.class), captor.capture());
            verify(plantAcclimationService, never()).scheduleNextCheckinById(anyLong());

            captor.getValue().onSuccess().run();

            verify(plantAcclimationService).scheduleNextCheckinById(10L);
        }

        @Test
        @DisplayName("Юзер заблокирован в рамках паузы (isPaused) → check-in не отправляется")
        void should_skip_checkin_when_user_paused() {
            User user = activeUser(100L, "Europe/Minsk");
            user.setPausedUntil(LocalDateTime.now().plusDays(1));
            Plant plant = plantOf(10L, "Монстера", user);
            when(plantAcclimationService.findPlantsForCheckin(any())).thenReturn(List.of(plant));

            scheduler.tick();

            verifyNoInteractions(telegramSender);
        }

        @Test
        @DisplayName("Невалидная TZ юзера падает в UTC-фолбэк, тик не падает, сообщение всё равно уходит")
        void should_fallback_to_utc_and_not_crash_when_timezone_invalid() {
            User user = activeUser(100L, "Not/AZone");
            Plant plant = plantOf(10L, "Монстера", user);
            when(plantAcclimationService.findPlantsForCheckin(any())).thenReturn(List.of(plant));

            scheduler.tick();

            // quiet-hours отключены (start==end) → сообщение уходит независимо от TZ-фолбэка.
            verify(telegramSender).enqueue(any(SendMessage.class), any(SendCallbacks.class));
        }

        @Test
        @DisplayName("Ошибка на одном растении не прерывает обработку остальных (checkin)")
        void should_continue_when_one_plant_throws_during_checkin() {
            Plant broken = plantOf(10L, "Сломанное", null);
            User healthyUser = activeUser(200L, "Europe/Minsk");
            Plant healthy = plantOf(20L, "Здоровое", healthyUser);
            when(plantAcclimationService.findPlantsForCheckin(any())).thenReturn(List.of(broken, healthy));

            scheduler.tick();

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender, times(1)).enqueue(captor.capture(), any(SendCallbacks.class));
            assertThat(captor.getValue().getChatId()).isEqualTo("200");
        }
    }

    @Test
    @DisplayName("Оба списка пусты → никаких взаимодействий с Telegram")
    void should_do_nothing_when_both_lists_empty() {
        scheduler.tick();

        verifyNoInteractions(telegramSender);
    }
}

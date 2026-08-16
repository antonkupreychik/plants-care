package com.plantcare.bot.service;

import com.plantcare.core.repository.UserRepository;
import com.plantcare.core.service.PlantAnniversaryService;
import com.plantcare.core.service.PlantAnniversaryService.Anniversary;
import com.plantcare.bot.telegram.RateLimitedTelegramSender;
import com.plantcare.bot.telegram.SendCallbacks;
import com.plantcare.core.domain.User;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link PlantAnniversaryScheduler} (issue #117) — не было ни одного
 * теста на этот класс до этого. Единственный из hourly-шедулеров, у которого
 * quiet/morning-проверка идёт через injected {@link Clock}, а не голый
 * {@code Instant.now()} — значит TZ-граница тестируется детерминированно
 * (в отличие от Acclimation/PhotoProgress, где TZ-проверка завязана на реальные
 * часы и это отдельно отмечено как находка в отчёте).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit-тесты для PlantAnniversaryScheduler (issue #117)")
class PlantAnniversarySchedulerTest {

    @Mock
    private PlantAnniversaryService plantAnniversaryService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RateLimitedTelegramSender telegramSender;

    @InjectMocks
    private PlantAnniversaryScheduler scheduler;

    private User almatyUser;
    private User utcUser;

    @BeforeEach
    void setUp() {
        almatyUser = User.builder().telegramChatId(700L).timezone("Asia/Almaty").blocked(false).build();
        ReflectionTestUtils.setField(almatyUser, "id", 7L);

        utcUser = User.builder().telegramChatId(800L).timezone("UTC").blocked(false).build();
        ReflectionTestUtils.setField(utcUser, "id", 8L);
    }

    private Anniversary anniversary(Long plantId, String name, Long userId, Long chatId,
                                     int years, long waterings, int year) {
        return new Anniversary(plantId, name, userId, chatId, years, waterings, year);
    }

    @Nested
    @DisplayName("Пустой список кандидатов")
    class NoDueAnniversaries {

        @Test
        @DisplayName("Ничего не отправляется, если findDueAnniversaries вернул пустой список")
        void should_do_nothing_when_no_due_anniversaries() {
            Clock clock = Clock.fixed(Instant.parse("2026-05-24T04:30:00Z"), ZoneOffset.UTC);
            ReflectionTestUtils.setField(scheduler, "clock", clock);
            when(plantAnniversaryService.findDueAnniversaries(any())).thenReturn(List.of());

            scheduler.tick();

            verifyNoInteractions(telegramSender, userRepository);
        }
    }

    @Nested
    @DisplayName("Утреннее окно: timezone-граница (issue #117 AC)")
    class MorningWindowTimezoneBoundary {

        /**
         * Один и тот же Instant (04:30 UTC) — для юзера в Asia/Almaty (UTC+5) это
         * 09:30 локально (внутри утреннего окна 09:00–10:00), а для юзера в UTC
         * это 04:30 (вне окна). Один и тот же тик шлёт одному и не шлёт другому —
         * доказывает, что проверка идёт в TZ юзера, а не в TZ инстанса.
         */
        @Test
        @DisplayName("Almaty (UTC+5) внутри окна 09:00-10:00 → шлёт; UTC-юзер на том же Instant → не шлёт")
        void should_send_only_to_almaty_user_when_instant_is_morning_in_almaty_but_not_in_utc() {
            Instant now = Instant.parse("2026-05-24T04:30:00Z");
            Clock clock = Clock.fixed(now, ZoneOffset.UTC);
            ReflectionTestUtils.setField(scheduler, "clock", clock);

            Anniversary almatyAnniv = anniversary(10L, "Алоэ", 7L, 700L, 3, 40, 2026);
            Anniversary utcAnniv = anniversary(20L, "Кактус", 8L, 800L, 2, 15, 2026);

            when(plantAnniversaryService.findDueAnniversaries(now))
                    .thenReturn(List.of(almatyAnniv, utcAnniv));
            when(userRepository.findById(7L)).thenReturn(Optional.of(almatyUser));
            when(userRepository.findById(8L)).thenReturn(Optional.of(utcUser));

            scheduler.tick();

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender, times(1)).enqueue(captor.capture(), any(SendCallbacks.class));
            assertThat(captor.getValue().getChatId()).isEqualTo("700");
            assertThat(captor.getValue().getText()).contains("Алоэ");
        }

        @Test
        @DisplayName("Almaty-юзер вне окна (13:00 локально) → не шлёт")
        void should_skip_when_outside_morning_window_in_user_zone() {
            // 08:00 UTC = 13:00 в Asia/Almaty (UTC+5) — не утро.
            Instant now = Instant.parse("2026-05-24T08:00:00Z");
            Clock clock = Clock.fixed(now, ZoneOffset.UTC);
            ReflectionTestUtils.setField(scheduler, "clock", clock);

            Anniversary anniv = anniversary(10L, "Алоэ", 7L, 700L, 3, 40, 2026);
            when(plantAnniversaryService.findDueAnniversaries(now)).thenReturn(List.of(anniv));
            when(userRepository.findById(7L)).thenReturn(Optional.of(almatyUser));

            scheduler.tick();

            verifyNoInteractions(telegramSender);
        }

        @Test
        @DisplayName("Пользователь из findById не найден (удалён между snapshot и тиком) → не шлёт, не падает")
        void should_skip_when_user_not_found() {
            Instant now = Instant.parse("2026-05-24T04:30:00Z");
            Clock clock = Clock.fixed(now, ZoneOffset.UTC);
            ReflectionTestUtils.setField(scheduler, "clock", clock);

            Anniversary anniv = anniversary(10L, "Алоэ", 7L, 700L, 3, 40, 2026);
            when(plantAnniversaryService.findDueAnniversaries(now)).thenReturn(List.of(anniv));
            when(userRepository.findById(7L)).thenReturn(Optional.empty());

            scheduler.tick();

            verifyNoInteractions(telegramSender);
        }
    }

    @Nested
    @DisplayName("onSuccess колбэк отмечает годовщину отправленной")
    class SuccessCallback {

        @Test
        @DisplayName("markSent вызывается только после успешного onSuccess, не при enqueue")
        void should_mark_sent_only_after_successful_send() {
            Instant now = Instant.parse("2026-05-24T04:30:00Z");
            Clock clock = Clock.fixed(now, ZoneOffset.UTC);
            ReflectionTestUtils.setField(scheduler, "clock", clock);

            Anniversary anniv = anniversary(10L, "Алоэ", 7L, 700L, 3, 40, 2026);
            when(plantAnniversaryService.findDueAnniversaries(now)).thenReturn(List.of(anniv));
            when(userRepository.findById(7L)).thenReturn(Optional.of(almatyUser));

            scheduler.tick();

            ArgumentCaptor<SendCallbacks> callbacksCaptor = ArgumentCaptor.forClass(SendCallbacks.class);
            verify(telegramSender).enqueue(any(SendMessage.class), callbacksCaptor.capture());
            verify(plantAnniversaryService, never()).markSent(anyLong(), anyInt(), any());

            callbacksCaptor.getValue().onSuccess().run();

            verify(plantAnniversaryService).markSent(10L, 2026, now);
        }
    }

    @Nested
    @DisplayName("Обработка ошибок на кандидата не роняет остальной тик")
    class ExceptionHandling {

        @Test
        @DisplayName("findById бросает для одной годовщины — вторая всё равно обрабатывается")
        void should_continue_processing_remaining_anniversaries_when_one_throws() {
            Instant now = Instant.parse("2026-05-24T04:30:00Z");
            Clock clock = Clock.fixed(now, ZoneOffset.UTC);
            ReflectionTestUtils.setField(scheduler, "clock", clock);

            Anniversary failing = anniversary(10L, "Алоэ", 7L, 700L, 3, 40, 2026);
            Anniversary healthy = anniversary(11L, "Фикус", 9L, 900L, 1, 5, 2026);
            User user9 = User.builder().telegramChatId(900L).timezone("Asia/Almaty").blocked(false).build();
            ReflectionTestUtils.setField(user9, "id", 9L);

            when(plantAnniversaryService.findDueAnniversaries(now))
                    .thenReturn(List.of(failing, healthy));
            when(userRepository.findById(7L)).thenThrow(new RuntimeException("DB hiccup"));
            when(userRepository.findById(9L)).thenReturn(Optional.of(user9));

            scheduler.tick();

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender, times(1)).enqueue(captor.capture(), any(SendCallbacks.class));
            assertThat(captor.getValue().getChatId()).isEqualTo("900");
            assertThat(captor.getValue().getText()).contains("Фикус");
        }
    }
}

package com.plantcare.bot.service;

import com.plantcare.bot.config.TransplantSuggestionProperties;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.repository.UserRepository;
import com.plantcare.bot.service.TransplantSuggestionService.DueSuggestion;
import com.plantcare.bot.telegram.RateLimitedTelegramSender;
import com.plantcare.bot.telegram.SendCallbacks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link TransplantSuggestionScheduler} (issue #141): оркестрация
 * выборки кандидатов → фиксации трекера → постановки нотификации в rate-limited
 * очередь. Окно горизонта и идемпотентность создания на уровне БД проверяются в
 * {@link TransplantSuggestionServiceTest} (Testcontainers); здесь мокаем сервис и
 * внешний мир (Telegram), проверяем именно поведение шедулера.
 *
 * <p>Telegram — внешний API, мокается. Идемпотентность шедулера: если
 * {@code recordSuggested} вернул empty (строку уже создал параллельный тик),
 * сообщение НЕ ставится в очередь.
 */
@DisplayName("TransplantSuggestionScheduler — оркестрация отправки (issue #141)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransplantSuggestionSchedulerTest {

    @Mock private TransplantSuggestionService suggestionService;
    @Mock private QuietHoursPolicy quietHoursPolicy;
    @Mock private UserRepository userRepository;
    @Mock private RateLimitedTelegramSender telegramSender;

    private static final Instant NOW = Instant.parse("2026-05-25T09:00:00Z");

    private TransplantSuggestionScheduler newScheduler(TransplantSuggestionProperties props) {
        return new TransplantSuggestionScheduler(
                suggestionService,
                props,
                quietHoursPolicy,
                userRepository,
                telegramSender,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static TransplantSuggestionProperties enabledProps() {
        return new TransplantSuggestionProperties(true, 14, 12, null, null);
    }

    @Test
    @DisplayName("should_record_and_enqueue_message_when_candidate_due_and_not_quiet")
    void should_record_and_enqueue_message_when_candidate_due_and_not_quiet() {
        // arrange
        TransplantSuggestionScheduler scheduler = newScheduler(enabledProps());
        DueSuggestion candidate = dueSuggestion(1L, 555L, 42L, "Фикус", 77L);
        when(suggestionService.findDueSuggestions(NOW)).thenReturn(List.of(candidate));
        when(userRepository.findById(1L)).thenReturn(Optional.of(notBlockedUser()));
        when(quietHoursPolicy.isQuiet(any(User.class), eq(NOW))).thenReturn(false);
        when(suggestionService.recordSuggested(candidate)).thenReturn(Optional.of(999L));
        when(suggestionService.buildMessageText("Фикус")).thenReturn("текст про Фикус");

        // act
        scheduler.tick();

        // assert: трекер зафиксирован ДО постановки в очередь, сообщение ушло на нужный чат
        verify(suggestionService).recordSuggested(candidate);
        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramSender, times(1)).enqueue(messageCaptor.capture(), any(SendCallbacks.class));
        assertThat(messageCaptor.getValue().getChatId()).isEqualTo("555");
        assertThat(messageCaptor.getValue().getText()).isEqualTo("текст про Фикус");
    }

    @Test
    @DisplayName("should_not_enqueue_when_recordSuggested_returns_empty_due_to_race")
    void should_not_enqueue_when_recordSuggested_returns_empty_due_to_race() {
        // arrange: параллельный тик уже создал строку → recordSuggested вернул empty.
        TransplantSuggestionScheduler scheduler = newScheduler(enabledProps());
        DueSuggestion candidate = dueSuggestion(1L, 555L, 42L, "Фикус", 77L);
        when(suggestionService.findDueSuggestions(NOW)).thenReturn(List.of(candidate));
        when(userRepository.findById(1L)).thenReturn(Optional.of(notBlockedUser()));
        when(quietHoursPolicy.isQuiet(any(User.class), eq(NOW))).thenReturn(false);
        when(suggestionService.recordSuggested(candidate)).thenReturn(Optional.empty());

        // act
        scheduler.tick();

        // assert: ничего не шлём — иначе кнопки уехали бы с чужим id
        verify(telegramSender, never()).enqueue(any(SendMessage.class), any(SendCallbacks.class));
    }

    @Test
    @DisplayName("should_not_enqueue_when_user_in_quiet_hours")
    void should_not_enqueue_when_user_in_quiet_hours() {
        // arrange
        TransplantSuggestionScheduler scheduler = newScheduler(enabledProps());
        DueSuggestion candidate = dueSuggestion(1L, 555L, 42L, "Фикус", 77L);
        when(suggestionService.findDueSuggestions(NOW)).thenReturn(List.of(candidate));
        when(userRepository.findById(1L)).thenReturn(Optional.of(notBlockedUser()));
        when(quietHoursPolicy.isQuiet(any(User.class), eq(NOW))).thenReturn(true);

        // act
        scheduler.tick();

        // assert: тихие часы — мягкую подсказку не шлём и трекер не создаём
        verify(suggestionService, never()).recordSuggested(any());
        verifyNoInteractions(telegramSender);
    }

    @Test
    @DisplayName("should_treat_missing_user_as_quiet_and_skip")
    void should_treat_missing_user_as_quiet_and_skip() {
        // arrange: юзер исчез между выборкой и отправкой → считаем «тихо», не шлём.
        TransplantSuggestionScheduler scheduler = newScheduler(enabledProps());
        DueSuggestion candidate = dueSuggestion(1L, 555L, 42L, "Фикус", 77L);
        when(suggestionService.findDueSuggestions(NOW)).thenReturn(List.of(candidate));
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        // act
        scheduler.tick();

        // assert
        verify(suggestionService, never()).recordSuggested(any());
        verifyNoInteractions(telegramSender);
    }

    @Test
    @DisplayName("should_do_nothing_when_no_candidates")
    void should_do_nothing_when_no_candidates() {
        // arrange
        TransplantSuggestionScheduler scheduler = newScheduler(enabledProps());
        when(suggestionService.findDueSuggestions(NOW)).thenReturn(List.of());

        // act
        scheduler.tick();

        // assert
        verifyNoInteractions(telegramSender);
        verify(suggestionService, never()).recordSuggested(any());
    }

    @Test
    @DisplayName("should_skip_feature_entirely_when_disabled")
    void should_skip_feature_entirely_when_disabled() {
        // arrange: enabled=false → шедулер даже не дёргает сервис.
        TransplantSuggestionProperties disabled =
                new TransplantSuggestionProperties(false, 14, 12, null, null);
        TransplantSuggestionScheduler scheduler = newScheduler(disabled);

        // act
        scheduler.tick();

        // assert
        verifyNoInteractions(suggestionService, telegramSender, userRepository, quietHoursPolicy);
    }

    @Test
    @DisplayName("should_continue_other_candidates_when_one_throws")
    void should_continue_other_candidates_when_one_throws() {
        // arrange: первый кандидат падает на recordSuggested, второй обрабатывается норм.
        TransplantSuggestionScheduler scheduler = newScheduler(enabledProps());
        DueSuggestion bad = dueSuggestion(1L, 111L, 42L, "Плохой", 77L);
        DueSuggestion good = dueSuggestion(2L, 222L, 43L, "Хороший", 78L);
        when(suggestionService.findDueSuggestions(NOW)).thenReturn(List.of(bad, good));
        when(userRepository.findById(any())).thenReturn(Optional.of(notBlockedUser()));
        when(quietHoursPolicy.isQuiet(any(User.class), eq(NOW))).thenReturn(false);
        when(suggestionService.recordSuggested(bad)).thenThrow(new RuntimeException("boom"));
        when(suggestionService.recordSuggested(good)).thenReturn(Optional.of(999L));
        when(suggestionService.buildMessageText("Хороший")).thenReturn("текст про Хороший");

        // act
        scheduler.tick();

        // assert: несмотря на падение первого, второй ушёл в очередь
        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramSender, times(1)).enqueue(messageCaptor.capture(), any(SendCallbacks.class));
        assertThat(messageCaptor.getValue().getChatId()).isEqualTo("222");
    }

    // ===================== helpers =====================

    private static DueSuggestion dueSuggestion(
            Long userId, Long chatId, Long plantId, String plantName, Long sourceEventId) {
        return new DueSuggestion(
                userId,
                chatId,
                plantId,
                plantName,
                sourceEventId,
                LocalDateTime.of(2026, 6, 1, 12, 0));
    }

    private static User notBlockedUser() {
        return User.builder().telegramChatId(555L).timezone("UTC").blocked(false).build();
    }
}

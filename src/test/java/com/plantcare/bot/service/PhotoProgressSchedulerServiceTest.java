package com.plantcare.bot.service;

import com.plantcare.core.service.PhotoProgressService;

import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.PhotoProgressFrequency;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.bot.telegram.RateLimitedTelegramSender;
import com.plantcare.bot.telegram.SendCallbacks;
import org.junit.jupiter.api.DisplayName;
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

import java.time.LocalDateTime;
import java.time.LocalTime;
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
 * Unit-тесты {@link PhotoProgressSchedulerService} (issue #72) после перевода на
 * rate-limited очередь (issue #29). Шедулер теперь не зовёт Telegram напрямую, а
 * кладёт сообщение в {@link RateLimitedTelegramSender#enqueue}; дедуп-пометка
 * {@code markPromptSent} переехала в onSuccess-колбэк. Тесты проверяют постановку
 * в очередь и маршрутизацию колбэков через захваченные {@link SendCallbacks}.
 */
@DisplayName("Unit-тесты PhotoProgressSchedulerService (issue #72 / #29)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PhotoProgressSchedulerServiceTest {

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private PhotoProgressService photoProgressService;

    @Mock
    private RateLimitedTelegramSender telegramSender;

    @InjectMocks
    private PhotoProgressSchedulerService service;

    @Test
    void should_enqueue_prompt_and_mark_on_success_when_plant_due_and_user_active() {
        User user = activeUser(100L);
        Plant plant = duePlant(10L, "Монстера", user);
        when(plantRepository.findPhotoProgressDue(any(), any())).thenReturn(List.of(plant));

        service.checkPhotoPrompts();

        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        ArgumentCaptor<SendCallbacks> callbacksCaptor = ArgumentCaptor.forClass(SendCallbacks.class);
        verify(telegramSender, times(1)).enqueue(messageCaptor.capture(), callbacksCaptor.capture());

        assertThat(messageCaptor.getValue().getChatId()).isEqualTo("100");
        // Пометка происходит ТОЛЬКО после успешной отправки (колбэк), не при enqueue.
        verify(photoProgressService, never()).markPromptSent(anyLong(), any(LocalDateTime.class));

        runSuccess(callbacksCaptor.getValue());

        verify(photoProgressService).markPromptSent(eq(plant.getId()), any(LocalDateTime.class));
    }

    @Test
    void should_not_mark_when_send_fails_callback_invoked() {
        User user = activeUser(100L);
        Plant plant = duePlant(10L, "Монстера", user);
        when(plantRepository.findPhotoProgressDue(any(), any())).thenReturn(List.of(plant));

        service.checkPhotoPrompts();

        ArgumentCaptor<SendCallbacks> callbacksCaptor = ArgumentCaptor.forClass(SendCallbacks.class);
        verify(telegramSender).enqueue(any(SendMessage.class), callbacksCaptor.capture());

        // Photo-progress использует fire-and-forget onFailure=null — провал не должен
        // ни ронять, ни помечать растение отправленным.
        runFailure(callbacksCaptor.getValue(),
                new org.telegram.telegrambots.meta.exceptions.TelegramApiException("boom"));

        verify(photoProgressService, never()).markPromptSent(anyLong(), any(LocalDateTime.class));
    }

    @Test
    void should_skip_when_user_is_paused() {
        User user = activeUser(100L);
        user.setPausedUntil(LocalDateTime.now().plusHours(2));
        Plant plant = duePlant(10L, "Монстера", user);
        when(plantRepository.findPhotoProgressDue(any(), any())).thenReturn(List.of(plant));

        service.checkPhotoPrompts();

        verifyNoInteractions(telegramSender);
        verify(photoProgressService, never()).markPromptSent(anyLong(), any(LocalDateTime.class));
    }

    @Test
    void should_skip_when_user_is_blocked() {
        User user = activeUser(100L);
        user.setBlocked(true);
        Plant plant = duePlant(10L, "Монстера", user);
        when(plantRepository.findPhotoProgressDue(any(), any())).thenReturn(List.of(plant));

        service.checkPhotoPrompts();

        verifyNoInteractions(telegramSender);
        verify(photoProgressService, never()).markPromptSent(anyLong(), any(LocalDateTime.class));
    }

    @Test
    void should_enqueue_each_due_plant_independently() {
        User user = activeUser(100L);
        Plant first = duePlant(10L, "Монстера", user);
        Plant second = duePlant(11L, "Фикус", user);
        when(plantRepository.findPhotoProgressDue(any(), any())).thenReturn(List.of(first, second));

        service.checkPhotoPrompts();

        ArgumentCaptor<SendCallbacks> callbacksCaptor = ArgumentCaptor.forClass(SendCallbacks.class);
        verify(telegramSender, times(2)).enqueue(any(SendMessage.class), callbacksCaptor.capture());

        // Каждый колбэк помечает своё растение — проверяем независимую маршрутизацию.
        List<SendCallbacks> allCallbacks = callbacksCaptor.getAllValues();
        runSuccess(allCallbacks.get(0));
        runSuccess(allCallbacks.get(1));

        verify(photoProgressService).markPromptSent(eq(first.getId()), any(LocalDateTime.class));
        verify(photoProgressService).markPromptSent(eq(second.getId()), any(LocalDateTime.class));
    }

    /** Эмулирует вызов onSuccess воркером очереди (с null-guard, как в проде). */
    private static void runSuccess(SendCallbacks callbacks) {
        if (callbacks.onSuccess() != null) {
            callbacks.onSuccess().run();
        }
    }

    /** Эмулирует вызов onFailure воркером очереди (с null-guard, как в проде). */
    private static void runFailure(SendCallbacks callbacks,
                                   org.telegram.telegrambots.meta.exceptions.TelegramApiException e) {
        if (callbacks.onFailure() != null) {
            callbacks.onFailure().accept(e);
        }
    }

    private static User activeUser(Long chatId) {
        User user = User.builder()
                .telegramChatId(chatId)
                .timezone("Europe/Minsk")
                .quietHoursStart(LocalTime.of(0, 0))
                .quietHoursEnd(LocalTime.of(0, 0))
                .blocked(false)
                .build();
        ReflectionTestUtils.setField(user, "id", chatId);
        return user;
    }

    private static Plant duePlant(Long id, String name, User user) {
        Plant plant = Plant.builder()
                .user(user)
                .name(name)
                .photoProgressFrequency(PhotoProgressFrequency.P2W)
                .build();
        plant.setNextPhotoDueAt(LocalDateTime.now().minusMinutes(5));
        ReflectionTestUtils.setField(plant, "id", id);
        return plant;
    }
}

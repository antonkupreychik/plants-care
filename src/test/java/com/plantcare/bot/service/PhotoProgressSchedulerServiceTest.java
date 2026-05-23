package com.plantcare.bot.service;

import com.plantcare.bot.client.TelegramClientProvider;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.PhotoProgressFrequency;
import com.plantcare.bot.repository.PlantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link PhotoProgressSchedulerService} (issue #72) — проверяют
 * базовое поведение: счастливый путь, фильтр paused/blocked, корректная
 * обработка падения Telegram-API для одного из растений.
 */
@DisplayName("Unit-тесты PhotoProgressSchedulerService (issue #72)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PhotoProgressSchedulerServiceTest {

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private PhotoProgressService photoProgressService;

    @Mock
    private TelegramClientProvider telegramClientProvider;

    @Mock
    private TelegramClient telegramClient;

    @InjectMocks
    private PhotoProgressSchedulerService service;

    @BeforeEach
    void setUp() {
        when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);
    }

    @Test
    void should_send_prompt_and_mark_when_plant_due_and_user_active() throws TelegramApiException {
        User user = activeUser(100L);
        Plant plant = duePlant(10L, "Монстера", user);
        when(plantRepository.findPhotoProgressDue(any(), any())).thenReturn(List.of(plant));

        service.checkPhotoPrompts();

        verify(telegramClient, times(1)).execute(any(SendMessage.class));
        verify(photoProgressService).markPromptSent(eq(plant.getId()), any(LocalDateTime.class));
    }

    @Test
    void should_skip_when_user_is_paused() throws TelegramApiException {
        User user = activeUser(100L);
        user.setPausedUntil(LocalDateTime.now().plusHours(2));
        Plant plant = duePlant(10L, "Монстера", user);
        when(plantRepository.findPhotoProgressDue(any(), any())).thenReturn(List.of(plant));

        service.checkPhotoPrompts();

        verify(telegramClient, never()).execute(any(SendMessage.class));
        verify(photoProgressService, never()).markPromptSent(anyLong(), any(LocalDateTime.class));
    }

    @Test
    void should_skip_when_user_is_blocked() throws TelegramApiException {
        User user = activeUser(100L);
        user.setBlocked(true);
        Plant plant = duePlant(10L, "Монстера", user);
        when(plantRepository.findPhotoProgressDue(any(), any())).thenReturn(List.of(plant));

        service.checkPhotoPrompts();

        verify(telegramClient, never()).execute(any(SendMessage.class));
        verify(photoProgressService, never()).markPromptSent(anyLong(), any(LocalDateTime.class));
    }

    @Test
    void should_continue_processing_when_telegram_fails_for_one_plant() throws TelegramApiException {
        User user = activeUser(100L);
        Plant failing = duePlant(10L, "Монстера", user);
        Plant ok = duePlant(11L, "Фикус", user);
        when(plantRepository.findPhotoProgressDue(any(), any())).thenReturn(List.of(failing, ok));

        // Первый вызов execute падает, второй проходит. Шедулер должен пометить
        // отправленным только второе растение, первое — пропустить.
        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiException("boom"))
                .thenReturn(null);

        service.checkPhotoPrompts();

        verify(telegramClient, times(2)).execute(any(SendMessage.class));
        verify(photoProgressService, never()).markPromptSent(eq(failing.getId()), any(LocalDateTime.class));
        verify(photoProgressService).markPromptSent(eq(ok.getId()), any(LocalDateTime.class));
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

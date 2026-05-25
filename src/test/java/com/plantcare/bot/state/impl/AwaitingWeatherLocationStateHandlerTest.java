package com.plantcare.bot.state.impl;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.core.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.location.Location;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AwaitingWeatherLocationStateHandler — issue #69")
class AwaitingWeatherLocationStateHandlerTest {

    @Mock private UserService userService;
    @Mock private UserRepository userRepository;
    @Mock private TelegramClient telegramClient;
    @Mock private Update update;
    @Mock private Message message;
    @Mock private Location telegramLocation;

    @InjectMocks
    private AwaitingWeatherLocationStateHandler handler;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(100L)
                .build();
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
    }

    @Test
    @DisplayName("getSupportedState() = AWAITING_WEATHER_LOCATION")
    void shouldReportCorrectState() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_WEATHER_LOCATION);
    }

    @Test
    @DisplayName("location-message → сохраняет lat/lon, переводит в IDLE, шлёт «✅ Локация сохранена»")
    void shouldSaveLocationAndAck() throws Exception {
        when(message.hasLocation()).thenReturn(true);
        when(message.getLocation()).thenReturn(telegramLocation);
        when(telegramLocation.getLatitude()).thenReturn(56.95);
        when(telegramLocation.getLongitude()).thenReturn(24.10);

        handler.handle(user, update, telegramClient);

        assertThat(user.getWeatherLat()).isEqualTo(56.95);
        assertThat(user.getWeatherLon()).isEqualTo(24.10);
        verify(userRepository).save(user);
        verify(userService).updateState(user, ConversationState.IDLE);

        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(sent.capture());
        assertThat(sent.getValue().getText()).contains("Локация сохранена");
        assertThat(sent.getValue().getReplyMarkup()).isInstanceOf(ReplyKeyboardRemove.class);
    }

    @Test
    @DisplayName("Текст «❌ Отмена» → state в IDLE, ответ «Отменено»")
    void shouldCancelOnCancelText() throws Exception {
        when(message.hasLocation()).thenReturn(false);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn("❌ Отмена");

        handler.handle(user, update, telegramClient);

        verify(userService).updateState(user, ConversationState.IDLE);
        verify(userRepository, never()).save(any());

        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(sent.capture());
        assertThat(sent.getValue().getText()).contains("Отменено");
    }

    @Test
    @DisplayName("Произвольный текст → re-prompt, БД не трогается")
    void shouldRepromptOnArbitraryText() throws Exception {
        when(message.hasLocation()).thenReturn(false);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn("случайный текст");

        handler.handle(user, update, telegramClient);

        verify(userRepository, never()).save(any());
        verify(userService, never()).updateState(eq(user), any());

        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(sent.capture());
        assertThat(sent.getValue().getText())
                .contains("Отправь локацию")
                .contains("Отмена");
    }
}

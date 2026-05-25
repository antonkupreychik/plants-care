package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.service.UserSettingsService;
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
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit-тесты {@link AwaitingQuietStartStateHandler} (issue #116).
 * Тихие часы законно пересекают полночь — ввод 22:00 не должен отвергаться,
 * валидируется только формат HH:mm.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AwaitingQuietStartStateHandler (#116)")
class AwaitingQuietStartStateHandlerTest {

    @Mock private UserService userService;
    @Mock private UserSettingsService userSettingsService;
    @Mock private TelegramClient telegramClient;

    @InjectMocks
    private AwaitingQuietStartStateHandler handler;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(100L)
                .timezone("Europe/Minsk")
                .conversationState(ConversationState.AWAITING_QUIET_START)
                .build();
    }

    @Test
    @DisplayName("getSupportedState == AWAITING_QUIET_START")
    void should_return_supported_state() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_QUIET_START);
    }

    @Test
    @DisplayName("Валидное «08:30» → setQuietHoursStart(08:30) + переход в IDLE")
    void should_set_quiet_hours_start_when_input_is_valid() throws TelegramApiException {
        handler.handle(user, textUpdate("08:30"), telegramClient);

        verify(userSettingsService).setQuietHoursStart(user, LocalTime.of(8, 30));
        verify(userService).updateState(user, ConversationState.IDLE);

        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(cap.capture());
        assertThat(cap.getValue().getText()).contains("08:30");
    }

    @Test
    @DisplayName("«22:00» (пересечение полуночи) — валидный ввод, НЕ отвергается")
    void should_accept_late_start_that_crosses_midnight() throws TelegramApiException {
        handler.handle(user, textUpdate("22:00"), telegramClient);

        verify(userSettingsService).setQuietHoursStart(user, LocalTime.of(22, 0));
        verify(userService).updateState(user, ConversationState.IDLE);
    }

    @Test
    @DisplayName("Невалидное время «99:99» → ошибка, остаёмся в состоянии, ничего не сохраняем")
    void should_reject_when_time_value_out_of_range() throws TelegramApiException {
        handler.handle(user, textUpdate("99:99"), telegramClient);

        verify(userSettingsService, never()).setQuietHoursStart(any(), any());
        verify(userService, never()).updateState(any(), any());

        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(cap.capture());
        assertThat(cap.getValue().getText())
                .contains("❌")
                .contains("ЧЧ:ММ");
    }

    @Test
    @DisplayName("Мусорный ввод «abc» → ошибка, остаёмся в состоянии, ничего не сохраняем")
    void should_reject_when_input_is_not_a_time() throws TelegramApiException {
        handler.handle(user, textUpdate("abc"), telegramClient);

        verify(userSettingsService, never()).setQuietHoursStart(any(), any());
        verify(userService, never()).updateState(any(), any());
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("Update без текста — handler молчит и ничего не дёргает")
    void should_ignore_update_without_text() throws TelegramApiException {
        Update update = new Update();
        update.setMessage(new Message());

        handler.handle(user, update, telegramClient);

        verify(userSettingsService, never()).setQuietHoursStart(any(), any());
        verify(telegramClient, never()).execute(any(SendMessage.class));
    }

    private Update textUpdate(String text) {
        Update u = new Update();
        Message m = new Message();
        m.setText(text);
        u.setMessage(m);
        return u;
    }
}

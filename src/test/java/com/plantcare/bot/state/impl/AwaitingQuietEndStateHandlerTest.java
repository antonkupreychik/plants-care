package com.plantcare.bot.state.impl;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.service.UserService;
import com.plantcare.core.service.UserSettingsService;
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
 * Unit-тесты {@link AwaitingQuietEndStateHandler} (issue #116).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AwaitingQuietEndStateHandler (#116)")
class AwaitingQuietEndStateHandlerTest {

    @Mock private UserService userService;
    @Mock private UserSettingsService userSettingsService;
    @Mock private TelegramClient telegramClient;

    @InjectMocks
    private AwaitingQuietEndStateHandler handler;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(100L)
                .timezone("Europe/Minsk")
                .conversationState(ConversationState.AWAITING_QUIET_END)
                .build();
    }

    @Test
    @DisplayName("getSupportedState == AWAITING_QUIET_END")
    void should_return_supported_state() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_QUIET_END);
    }

    @Test
    @DisplayName("Валидное «09:00» → setQuietHoursEnd(09:00) + переход в IDLE")
    void should_set_quiet_hours_end_when_input_is_valid() throws TelegramApiException {
        handler.handle(user, textUpdate("09:00"), telegramClient);

        verify(userSettingsService).setQuietHoursEnd(user, LocalTime.of(9, 0));
        verify(userService).updateState(user, ConversationState.IDLE);

        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(cap.capture());
        assertThat(cap.getValue().getText()).contains("09:00");
    }

    @Test
    @DisplayName("Мусорный ввод «25:61» → ошибка, остаёмся в состоянии, ничего не сохраняем")
    void should_reject_when_time_value_out_of_range() throws TelegramApiException {
        handler.handle(user, textUpdate("25:61"), telegramClient);

        verify(userSettingsService, never()).setQuietHoursEnd(any(), any());
        verify(userService, never()).updateState(any(), any());

        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(cap.capture());
        assertThat(cap.getValue().getText())
                .contains("❌")
                .contains("ЧЧ:ММ");
    }

    private Update textUpdate(String text) {
        Update u = new Update();
        Message m = new Message();
        m.setText(text);
        u.setMessage(m);
        return u;
    }
}

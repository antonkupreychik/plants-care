package com.plantcare.bot.state.impl;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.service.UserService;
import com.plantcare.core.service.UserSettingsService;
import com.plantcare.core.service.UserSettingsService.TimezoneChangeResult;
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
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link AwaitingTimezoneCustomStateHandler} (issue #116).
 * Внешний мир (UserService, UserSettingsService, TelegramClient) — моки.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AwaitingTimezoneCustomStateHandler (#116)")
class AwaitingTimezoneCustomStateHandlerTest {

    @Mock private UserService userService;
    @Mock private UserSettingsService userSettingsService;
    @Mock private TelegramClient telegramClient;

    @InjectMocks
    private AwaitingTimezoneCustomStateHandler handler;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(100L)
                .timezone("Europe/Minsk")
                .quietHoursStart(LocalTime.of(22, 0))
                .quietHoursEnd(LocalTime.of(9, 0))
                .conversationState(ConversationState.AWAITING_TIMEZONE_CUSTOM)
                .build();
    }

    @Test
    @DisplayName("getSupportedState == AWAITING_TIMEZONE_CUSTOM")
    void should_return_supported_state() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_TIMEZONE_CUSTOM);
    }

    @Test
    @DisplayName("Валидный ZoneId (Asia/Tbilisi) → changeTimezone + переход в IDLE + подтверждение")
    void should_change_timezone_and_go_idle_when_zone_id_is_valid() throws TelegramApiException {
        when(userSettingsService.changeTimezone(eq(user), eq(ZoneId.of("Asia/Tbilisi"))))
                .thenReturn(new TimezoneChangeResult("Asia/Tbilisi", 2, false));

        handler.handle(user, textUpdate("Asia/Tbilisi"), telegramClient);

        verify(userSettingsService).changeTimezone(user, ZoneId.of("Asia/Tbilisi"));
        verify(userService).updateState(user, ConversationState.IDLE);

        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(cap.capture());
        assertThat(cap.getValue().getText())
                .contains("Asia/Tbilisi");
    }

    @Test
    @DisplayName("Невалидный ZoneId (Mars/Olympus) → changeTimezone НЕ зовётся, остаёмся в состоянии, ошибка")
    void should_reprompt_and_stay_in_state_when_zone_id_is_invalid() throws TelegramApiException {
        handler.handle(user, textUpdate("Mars/Olympus"), telegramClient);

        verify(userSettingsService, never()).changeTimezone(any(), any());
        verify(userService, never()).updateState(any(), any());

        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(cap.capture());
        assertThat(cap.getValue().getText())
                .contains("❌")
                .contains("таймзону");
    }

    @Test
    @DisplayName("Update без текста — handler молчит и ничего не дёргает")
    void should_ignore_update_without_text() throws TelegramApiException {
        Update update = new Update();
        update.setMessage(new Message());

        handler.handle(user, update, telegramClient);

        verify(userSettingsService, never()).changeTimezone(any(), any());
        verify(userService, never()).updateState(any(), any());
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

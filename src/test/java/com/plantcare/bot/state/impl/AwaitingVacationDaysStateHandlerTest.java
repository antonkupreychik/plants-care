package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.UserService;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты для AwaitingVacationDaysStateHandler (issue #53).
 *
 * Внешние зависимости (UserService, TelegramClient) — моки.
 * Поведение по краям: невалидный ввод не должен ронять стейт, валидный сбрасывает в IDLE.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AwaitingVacationDaysStateHandler (issue #53)")
class AwaitingVacationDaysStateHandlerTest {

    @Mock private UserService userService;
    @Mock private TelegramClient telegramClient;

    @InjectMocks
    private AwaitingVacationDaysStateHandler handler;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(100L)
                .timezone("Europe/Minsk")
                .conversationState(ConversationState.AWAITING_VACATION_DAYS)
                .build();
    }

    @Test
    @DisplayName("getSupportedState возвращает AWAITING_VACATION_DAYS")
    void should_return_supported_state() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_VACATION_DAYS);
    }

    @Test
    @DisplayName("Валидное число → startVacation + resetToIdle + подтверждение в чат")
    void should_start_vacation_when_user_enters_valid_number() throws TelegramApiException {
        User updated = userWithPausedUntil(LocalDateTime.of(2026, 5, 30, 10, 0));
        when(userService.startVacation(user, 7)).thenReturn(updated);

        handler.handle(user, textUpdate("7"), telegramClient);

        verify(userService).startVacation(user, 7);
        verify(userService).resetToIdle(updated);

        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(cap.capture());
        assertThat(cap.getValue().getText())
                .contains("🏖")
                .contains("Отпуск до");
    }

    @Test
    @DisplayName("Не-число → ошибка «Введи целое число от 1 до 60», startVacation не зовётся")
    void should_reject_when_user_enters_non_number() throws TelegramApiException {
        handler.handle(user, textUpdate("пять"), telegramClient);

        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(cap.capture());
        assertThat(cap.getValue().getText())
                .contains("❌")
                .contains("целое число")
                .contains("1")
                .contains("60");

        verify(userService, never()).startVacation(any(), anyInt());
        verify(userService, never()).resetToIdle(any());
    }

    @Test
    @DisplayName("Число вне диапазона → ловим IllegalArgumentException и шлём текст ошибки, state не сбрасываем")
    void should_reject_when_number_out_of_range() throws TelegramApiException {
        when(userService.startVacation(user, 100))
                .thenThrow(new IllegalArgumentException("Дней должно быть от 1 до 60"));

        handler.handle(user, textUpdate("100"), telegramClient);

        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(cap.capture());
        assertThat(cap.getValue().getText())
                .contains("❌")
                .contains("Дней должно быть");

        verify(userService, never()).resetToIdle(any());
    }

    @Test
    @DisplayName("Пробелы вокруг числа триммятся: «  7  » → startVacation(user, 7)")
    void should_handle_whitespace_in_input() throws TelegramApiException {
        User updated = userWithPausedUntil(LocalDateTime.of(2026, 5, 30, 10, 0));
        when(userService.startVacation(user, 7)).thenReturn(updated);

        handler.handle(user, textUpdate("  7  "), telegramClient);

        verify(userService).startVacation(user, 7);
        verify(userService).resetToIdle(updated);
    }

    @Test
    @DisplayName("Update без текста — handler молчит и ничего не дёргает")
    void should_ignore_update_without_text() throws TelegramApiException {
        Update update = new Update();
        update.setMessage(new Message());

        handler.handle(user, update, telegramClient);

        verify(userService, never()).startVacation(any(), anyInt());
        verify(telegramClient, never()).execute(any(SendMessage.class));
    }

    private Update textUpdate(String text) {
        Update u = new Update();
        Message m = new Message();
        m.setText(text);
        u.setMessage(m);
        return u;
    }

    private User userWithPausedUntil(LocalDateTime pausedUntil) {
        User u = User.builder()
                .telegramChatId(user.getTelegramChatId())
                .timezone(user.getTimezone())
                .pausedUntil(pausedUntil)
                .build();
        return u;
    }
}

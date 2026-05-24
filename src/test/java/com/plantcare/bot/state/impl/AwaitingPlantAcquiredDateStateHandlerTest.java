package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit-тесты ручного ввода даты «когда завёл растение» (issue #117, Part A.1).
 *
 * <p>Хендлер парсит строку формата {@code DD.MM.YYYY}, отвергает будущее
 * (относительно «сегодня» в TZ юзера) и относительно невалидный формат —
 * с разными сообщениями об ошибке.
 */
@DisplayName("AwaitingPlantAcquiredDateStateHandler — ручной ввод даты «С тобой с …»")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AwaitingPlantAcquiredDateStateHandlerTest {

    @Mock private UserService userService;
    @Mock private TelegramClient client;

    private AwaitingPlantAcquiredDateStateHandler handler;
    private User user;

    @BeforeEach
    void setUp() {
        handler = new AwaitingPlantAcquiredDateStateHandler(userService);
        user = User.builder()
                .telegramChatId(555L)
                .timezone("UTC")
                .stateData(new HashMap<>())
                .build();
    }

    @Test
    @DisplayName("should_save_iso_date_when_valid_dd_mm_yyyy")
    void should_save_iso_date_when_valid_dd_mm_yyyy() throws TelegramApiException {
        // arrange
        user.getStateData().put("plant_name", "Монстера");
        Update update = textUpdate("15.03.2024");

        // act
        handler.handle(user, update, client);

        // assert
        verify(userService).setStateData(eq(user),
                eq(AwaitingPlantAcquiredChoiceStateHandler.STATE_KEY_ACQUIRED_AT),
                eq("2024-03-15"));
        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_LAST_WATERED);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText())
                .contains("Когда ты в последний раз поливал(а)")
                .contains("Монстера");
    }

    @Test
    @DisplayName("should_reject_future_date_and_not_advance_state")
    void should_reject_future_date_and_not_advance_state() throws TelegramApiException {
        // arrange: дата гарантированно в будущем (через 25 лет от любой возможной TZ)
        Update update = textUpdate("01.01.2099");

        // act
        handler.handle(user, update, client);

        // assert
        verify(userService, never()).setStateData(any(), anyString(), anyString());
        verify(userService, never()).updateState(any(), any());

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("не может быть в будущем");
    }

    @Test
    @DisplayName("should_reject_malformed_input_with_format_hint")
    void should_reject_malformed_input_with_format_hint() throws TelegramApiException {
        // arrange
        Update update = textUpdate("32.13.2024");

        // act
        handler.handle(user, update, client);

        // assert
        verify(userService, never()).setStateData(any(), anyString(), anyString());
        verify(userService, never()).updateState(any(), any());

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText())
                .contains("формат")
                .contains("15.03.2024");
    }

    @Test
    @DisplayName("should_reject_non_date_text_like_yesterday")
    void should_reject_non_date_text_like_yesterday() throws TelegramApiException {
        // arrange
        Update update = textUpdate("вчера");

        // act
        handler.handle(user, update, client);

        // assert
        verify(userService, never()).setStateData(any(), anyString(), anyString());
        verify(userService, never()).updateState(any(), any());
        verify(client).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("should_ignore_non_text_update_silently")
    void should_ignore_non_text_update_silently() throws TelegramApiException {
        // arrange: update без message — например, callback вместо ввода
        Update update = new Update();

        // act
        handler.handle(user, update, client);

        // assert
        verify(userService, never()).setStateData(any(), anyString(), anyString());
        verify(userService, never()).updateState(any(), any());
        verify(client, never()).execute(any(SendMessage.class));
    }

    private Update textUpdate(String text) {
        Update update = new Update();
        Message message = new Message();
        message.setText(text);
        update.setMessage(message);
        return update;
    }
}

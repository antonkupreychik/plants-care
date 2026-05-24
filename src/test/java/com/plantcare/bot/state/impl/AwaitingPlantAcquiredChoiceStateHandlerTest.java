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
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit-тесты пресетов «Сегодня / На этой неделе / Месяц назад / Пропустить / Давно»
 * для шага «когда завёл растение?» (issue #117, Part A.2).
 *
 * <p>Хендлер для пресетов сам считает {@link LocalDate} в TZ юзера через
 * {@code LocalDate.now(zoneId)}. Из-за этого тесты не могут проверять «ровно
 * это число» без подмены Clock — но они могут проверить:
 *   - что для не-UTC TZ дата считается именно по календарю в этой TZ (через
 *     эквивалентность с прямым вызовом {@link LocalDate#now(ZoneId)});
 *   - что состояние/state_data меняются корректно и что callback всегда
 *     закрывается AnswerCallbackQuery.
 */
@DisplayName("AwaitingPlantAcquiredChoiceStateHandler — пресеты «когда завёл?»")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AwaitingPlantAcquiredChoiceStateHandlerTest {

    @Mock private UserService userService;
    @Mock private TelegramClient client;

    private AwaitingPlantAcquiredChoiceStateHandler handler;
    private User user;

    @BeforeEach
    void setUp() {
        handler = new AwaitingPlantAcquiredChoiceStateHandler(userService);
        user = User.builder()
                .telegramChatId(777L)
                .timezone("Asia/Almaty")
                .stateData(new HashMap<>())
                .build();
    }

    @Test
    @DisplayName("should_save_today_in_user_zone_when_today_preset_pressed")
    void should_save_today_in_user_zone_when_today_preset_pressed() throws TelegramApiException {
        // arrange
        Update update = callbackUpdate("ACQUIRED:TODAY");
        LocalDate expectedToday = LocalDate.now(ZoneId.of("Asia/Almaty"));

        // act
        handler.handle(user, update, client);

        // assert: state_data содержит ISO-дату «сегодня в Алматы».
        // Сравнение допускает гонку через полночь UTC vs Almaty — оба значения
        // считаются в одной TZ, потому различие может быть только если
        // тест буквально пересёк полночь между двумя вызовами now().
        ArgumentCaptor<String> dateCaptor = ArgumentCaptor.forClass(String.class);
        verify(userService).setStateData(eq(user),
                eq(AwaitingPlantAcquiredChoiceStateHandler.STATE_KEY_ACQUIRED_AT),
                dateCaptor.capture());

        LocalDate saved = LocalDate.parse(dateCaptor.getValue());
        assertThat(saved).isCloseTo(expectedToday, within(1));

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_LAST_WATERED);
        verify(client).execute(any(AnswerCallbackQuery.class));
    }

    @Test
    @DisplayName("should_save_today_minus_3_days_when_week_preset")
    void should_save_today_minus_3_days_when_week_preset() throws TelegramApiException {
        // arrange
        Update update = callbackUpdate("ACQUIRED:WEEK");
        LocalDate expected = LocalDate.now(ZoneId.of("Asia/Almaty")).minusDays(3);

        // act
        handler.handle(user, update, client);

        // assert
        ArgumentCaptor<String> dateCaptor = ArgumentCaptor.forClass(String.class);
        verify(userService).setStateData(eq(user),
                eq(AwaitingPlantAcquiredChoiceStateHandler.STATE_KEY_ACQUIRED_AT),
                dateCaptor.capture());

        LocalDate saved = LocalDate.parse(dateCaptor.getValue());
        assertThat(saved).isCloseTo(expected, within(1));
    }

    @Test
    @DisplayName("should_save_today_minus_30_days_when_month_preset")
    void should_save_today_minus_30_days_when_month_preset() throws TelegramApiException {
        // arrange
        Update update = callbackUpdate("ACQUIRED:MONTH");
        LocalDate expected = LocalDate.now(ZoneId.of("Asia/Almaty")).minusDays(30);

        // act
        handler.handle(user, update, client);

        // assert
        ArgumentCaptor<String> dateCaptor = ArgumentCaptor.forClass(String.class);
        verify(userService).setStateData(eq(user),
                eq(AwaitingPlantAcquiredChoiceStateHandler.STATE_KEY_ACQUIRED_AT),
                dateCaptor.capture());

        LocalDate saved = LocalDate.parse(dateCaptor.getValue());
        assertThat(saved).isCloseTo(expected, within(1));
    }

    @Test
    @DisplayName("should_remove_acquired_when_skip_preset")
    void should_remove_acquired_when_skip_preset() throws TelegramApiException {
        // arrange
        Update update = callbackUpdate("ACQUIRED:SKIP");

        // act
        handler.handle(user, update, client);

        // assert
        verify(userService).removeStateData(user,
                AwaitingPlantAcquiredChoiceStateHandler.STATE_KEY_ACQUIRED_AT);
        verify(userService, never()).setStateData(any(),
                eq(AwaitingPlantAcquiredChoiceStateHandler.STATE_KEY_ACQUIRED_AT),
                anyString());
        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_LAST_WATERED);
        verify(client).execute(any(AnswerCallbackQuery.class));
    }

    @Test
    @DisplayName("should_switch_to_manual_date_state_when_manual_preset")
    void should_switch_to_manual_date_state_when_manual_preset() throws TelegramApiException {
        // arrange
        Update update = callbackUpdate("ACQUIRED:MANUAL");

        // act
        handler.handle(user, update, client);

        // assert
        verify(userService, never()).setStateData(any(),
                eq(AwaitingPlantAcquiredChoiceStateHandler.STATE_KEY_ACQUIRED_AT),
                anyString());
        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_ACQUIRED_DATE);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText())
                .contains("ДД.ММ.ГГГГ")
                .contains("15.03.2024");
    }

    @Test
    @DisplayName("should_ignore_unknown_choice_without_state_change")
    void should_ignore_unknown_choice_without_state_change() throws TelegramApiException {
        // arrange
        Update update = callbackUpdate("ACQUIRED:WHATEVER");

        // act
        handler.handle(user, update, client);

        // assert: ни одного setStateData / updateState, но callback закрыт
        verify(userService, never()).setStateData(any(), anyString(), anyString());
        verify(userService, never()).removeStateData(any(), anyString());
        verify(userService, never()).updateState(any(), any());
        verify(client).execute(any(AnswerCallbackQuery.class));
    }

    private static org.assertj.core.data.TemporalUnitOffset within(long days) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(
                days, java.time.temporal.ChronoUnit.DAYS);
    }

    private Update callbackUpdate(String data) {
        Update update = new Update();
        CallbackQuery cq = new CallbackQuery();
        cq.setId("cb-1");
        cq.setData(data);
        update.setCallbackQuery(cq);
        return update;
    }
}

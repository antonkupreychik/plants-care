package com.plantcare.bot.state.impl;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.service.UserService;
import com.plantcare.bot.support.IntegrationTestBase;
import net.iakovlev.timeshape.TimeZoneEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.location.Location;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Тесты основного обработчика таймзоны")
class AwaitingTimezoneStateHandlerTest extends IntegrationTestBase {

    @Autowired
    private AwaitingTimezoneStateHandler handler;

    @Autowired
    private UserService userService;

    @MockitoBean
    private TelegramClient telegramClient;

    @MockitoBean
    private TimeZoneEngine timeZoneEngine;

    private User testUser;

    private final Long chatId = 555L;

    @BeforeEach
    void setUp() {
        testUser = userService.findOrCreate(chatId, "location_user");
        userService.updateState(testUser, ConversationState.AWAITING_TIMEZONE);
        reset(telegramClient, timeZoneEngine);
    }

    @Test
    @DisplayName("Успешное определение таймзоны по локации возвращает пользователя в IDLE")
    void shouldSetTimezoneAndReturnToIdleWhenLocationReceived() throws TelegramApiException {
        double lat = 53.9006;
        double lon = 27.5590;
        String zoneId = "Europe/Minsk";

        Update update = mock(Update.class);
        Message message = mock(Message.class);
        Location location = mock(Location.class);

        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasLocation()).thenReturn(true);
        when(message.getLocation()).thenReturn(location);
        when(location.getLatitude()).thenReturn(lat);
        when(location.getLongitude()).thenReturn(lon);
        when(timeZoneEngine.query(lat, lon)).thenReturn(Optional.of(ZoneId.of(zoneId)));

        handler.handle(testUser, update, telegramClient);

        User reloadedUser = userService.findByChatId(chatId).orElseThrow();

        assertThat(reloadedUser.getTimezone()).isEqualTo(zoneId);
        assertThat(reloadedUser.getConversationState()).isEqualTo(ConversationState.IDLE);

        verify(telegramClient).execute(argThat((SendMessage msg) ->
                msg.getText().contains("Часовой пояс обновлён")
                        && msg.getText().contains(zoneId)
                        && msg.getText().contains("/menu")
        ));
    }

    @Test
    @DisplayName("Переход в ручной выбор таймзоны")
    void shouldTransitionToManualModeOnButtonClick() throws TelegramApiException {
        Update update = mock(Update.class);
        Message message = mock(Message.class);

        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasLocation()).thenReturn(false);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn("⌨️ Выбрать вручную");

        handler.handle(testUser, update, telegramClient);

        User reloadedUser = userService.findByChatId(chatId).orElseThrow();

        assertThat(reloadedUser.getConversationState()).isEqualTo(ConversationState.AWAITING_TIMEZONE_MANUAL);

        verify(telegramClient).execute(argThat((SendMessage msg) ->
                msg.getText().contains("Выбери свой регион")
                        || msg.getText().contains("Выбери свой город")
        ));
    }

    @Test
    @DisplayName("Если таймзона по координатам не найдена, показывается ручной выбор")
    void shouldSendErrorWhenLocationNotFound() throws TelegramApiException {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        Location location = mock(Location.class);

        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasLocation()).thenReturn(true);
        when(message.getLocation()).thenReturn(location);
        when(location.getLatitude()).thenReturn(0.0);
        when(location.getLongitude()).thenReturn(0.0);
        when(timeZoneEngine.query(0.0, 0.0)).thenReturn(Optional.empty());

        handler.handle(testUser, update, telegramClient);

        User reloadedUser = userService.findByChatId(chatId).orElseThrow();

        assertThat(reloadedUser.getConversationState()).isEqualTo(ConversationState.AWAITING_TIMEZONE);

        verify(telegramClient, times(2)).execute(any(SendMessage.class));
    }
}
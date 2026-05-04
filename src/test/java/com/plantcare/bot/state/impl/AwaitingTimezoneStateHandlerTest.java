package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.support.IntegrationTestBase;
import net.iakovlev.timeshape.TimeZoneEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.location.Location;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.Serializable;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Тесты основного обработчика таймзоны (локация/выбор)")
class AwaitingTimezoneStateHandlerTest extends IntegrationTestBase {

    @Autowired
    private AwaitingTimezoneStateHandler handler;

    @Autowired
    private UserService userService;

    @MockBean
    private TelegramClient telegramClient;

    @MockBean
    private TimeZoneEngine timeZoneEngine;

    private User testUser;
    private final Long chatId = 555L;

    @BeforeEach
    void setUp() {
        testUser = userService.findOrCreate(chatId, "location_user");
        userService.updateState(testUser, ConversationState.AWAITING_TIMEZONE);
    }

    @Test
    @DisplayName("Успешное определение таймзоны по локации")
    void shouldSetTimezoneWhenLocationReceived() throws TelegramApiException {
        // GIVEN
        double lat = 56.9496; // Рига
        double lon = 24.1052;
        String zoneId = "Europe/Riga";

        Update update = mock(Update.class);
        Message message = mock(Message.class);
        Location location = mock(Location.class);

        when(update.hasMessage()).thenReturn(true);
        when(message.hasLocation()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.getLocation()).thenReturn(location);
        when(location.getLatitude()).thenReturn(lat);
        when(location.getLongitude()).thenReturn(lon);

        // Мокаем TimeShape
        when(timeZoneEngine.query(lat, lon)).thenReturn(Optional.of(ZoneId.of(zoneId)));

        // WHEN
        handler.handle(testUser, update, telegramClient);

        // THEN
        assertThat(testUser.getTimezone()).isEqualTo(zoneId);
        assertThat(testUser.getConversationState()).isEqualTo(ConversationState.AWAITING_PLANT_NAME);
        
        verify(telegramClient).execute((BotApiMethod<Serializable>) argThat(msg -> {
            if (msg instanceof SendMessage sm) {
                return sm.getText().contains("установлен: *Europe/Riga*");
            }
            return false;
        }));
    }

    @Test
    @DisplayName("Переход в ручной режим при нажатии кнопки")
    void shouldTransitionToManualModeOnButtonClick() throws TelegramApiException {
        // GIVEN
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.getText()).thenReturn("⌨️ Выбрать вручную");

        // WHEN
        handler.handle(testUser, update, telegramClient);

        // THEN
        assertThat(testUser.getConversationState()).isEqualTo(ConversationState.AWAITING_TIMEZONE_MANUAL);
        
        // Должна быть отправлена инлайн-клавиатура
        verify(telegramClient).execute((BotApiMethod<Serializable>) argThat(msg -> {
            if (msg instanceof SendMessage sm) {
                return sm.getText().contains("Выбери свой город");
            }
            return false;
        }));
    }

    @Test
    @DisplayName("Обработка ошибки, когда координаты не найдены")
    void shouldSendErrorWhenLocationNotFound() throws TelegramApiException {
        // GIVEN
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        Location location = mock(Location.class);

        when(update.hasMessage()).thenReturn(true);
        when(message.hasLocation()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.getLocation()).thenReturn(location);
        when(location.getLatitude()).thenReturn(0.0);
        when(location.getLongitude()).thenReturn(0.0);

        // Таймзона не найдена
        when(timeZoneEngine.query(0.0, 0.0)).thenReturn(Optional.empty());

        // WHEN
        handler.handle(testUser, update, telegramClient);

        // THEN
        // Состояние не должно измениться на PLANT_NAME, юзер остается в онбординге
        assertThat(testUser.getConversationState()).isNotEqualTo(ConversationState.AWAITING_PLANT_NAME);

        // Должна быть ошибка и следом за ней — выбор вручную
        verify(telegramClient, times(2)).execute(any(SendMessage.class));
    }
}
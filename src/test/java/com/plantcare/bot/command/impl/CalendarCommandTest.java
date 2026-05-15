package com.plantcare.bot.command.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.featureflag.FeatureFlag;
import com.plantcare.bot.service.CalendarMenuService;
import com.plantcare.bot.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CalendarCommand — gated by FeatureFlag.CALENDAR (#78)")
class CalendarCommandTest {

    @Mock private UserService userService;
    @Mock private CalendarMenuService calendarMenuService;
    @Mock private TelegramClient telegramClient;
    @Mock private Update update;
    @Mock private Message message;

    @InjectMocks
    private CalendarCommand command;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(100L)
                .featureFlags(new HashMap<>())
                .build();
        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(100L);
        when(userService.findByChatId(100L)).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("Флаг включён → CalendarMenuService.sendCalendar вызывается")
    void shouldSendWhenEnabled() {
        user.getFeatureFlags().put(FeatureFlag.CALENDAR, "true");

        command.execute(update, telegramClient);

        verify(calendarMenuService).sendCalendar(user, telegramClient);
    }

    @Test
    @DisplayName("Флаг выключен → sendCalendar НЕ вызывается, шлётся заглушка")
    void shouldSendStubWhenDisabled() throws Exception {
        // featureFlags пустой — флаг считается выключенным

        command.execute(update, telegramClient);

        verify(calendarMenuService, never()).sendCalendar(any(User.class), any());
        verify(telegramClient).execute(any(
                org.telegram.telegrambots.meta.api.methods.send.SendMessage.class));
    }

    @Test
    @DisplayName("Флаг присутствует со значением 'false' → как выключенный")
    void shouldTreatExplicitFalseAsDisabled() {
        Map<String, Object> flags = new HashMap<>();
        flags.put(FeatureFlag.CALENDAR, "false");
        user.setFeatureFlags(flags);

        command.execute(update, telegramClient);

        verify(calendarMenuService, never()).sendCalendar(any(User.class), any());
    }
}

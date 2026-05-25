package com.plantcare.bot.beans;

import com.plantcare.bot.command.CommandContainer;
import com.plantcare.bot.command.impl.CancelCommand;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.service.DiseaseMenuService;
import com.plantcare.bot.service.MenuCallbackService;
import com.plantcare.bot.service.NotificationCallbackService;
import com.plantcare.bot.service.NotificationDigestCallbackService;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.state.StateResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.Update;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Регрессионный тест диспетчера {@link PlantsCareBot} (issue #116).
 *
 * <p>Раньше callback с data, начинающейся на {@code "SET_TZ"} (как {@code SET_TZ:<zone>}
 * из inline-пикера, так и {@code SET_TZ_CUSTOM}), не попадал ни в один prefix-ветку и
 * проваливался в "Unhandled callback" — пользователь видел крутящийся спиннер, который
 * исчезал по таймауту, выбор таймзоны молча терялся.
 *
 * <p>Эти тесты прогоняют именно диспетчер ({@code consume(Update)}), а не хендлер
 * напрямую, чтобы dead-callback-баг ловился на уровне маршрутизации: проверяем, что
 * {@code SET_TZ*}-callback уходит в {@link StateResolver#resolve}, а НЕ в
 * {@link MenuCallbackService#handleCallback}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlantsCareBot dispatcher — маршрутизация SET_TZ callback (#116)")
class PlantsCareBotTest {

    private static final Long CHAT_ID = 4242L;

    @Mock
    private CommandContainer commandContainer;
    @Mock
    private UserService userService;
    @Mock
    private StateResolver stateResolver;
    @Mock
    private CancelCommand cancelCommand;
    @Mock
    private NotificationCallbackService notificationCallbackService;
    @Mock
    private NotificationDigestCallbackService notificationDigestCallbackService;
    @Mock
    private MenuCallbackService menuCallbackService;
    @Mock
    private DiseaseMenuService diseaseMenuService;
    @Mock
    private com.plantcare.bot.service.TransplantSuggestionCallbackService transplantSuggestionCallbackService;

    private PlantsCareBot bot;

    @BeforeEach
    void setUp() {
        // Конструктор зеркалит PlantsCareBot.java:55-64. Токен — любой непустой:
        // telegramClient (OkHttpTelegramClient) создаётся внутри, в маршрутизации не участвует.
        bot = new PlantsCareBot(
                "test:token",
                commandContainer,
                userService,
                stateResolver,
                cancelCommand,
                notificationCallbackService,
                notificationDigestCallbackService,
                menuCallbackService,
                diseaseMenuService,
                transplantSuggestionCallbackService
        );
    }

    @Test
    @DisplayName("SET_TZ:<zone> маршрутизируется в stateResolver.resolve, а не в menuCallbackService")
    void should_route_to_state_resolver_when_callback_data_is_set_tz_zone() {
        // arrange
        User user = new User();
        when(userService.findOrCreate(eq(CHAT_ID), any())).thenReturn(user);
        Update update = callbackUpdate("SET_TZ:Asia/Almaty");

        // act
        bot.consume(update);

        // assert
        verify(stateResolver).resolve(eq(user), eq(update), any());
        verify(menuCallbackService, never()).handleCallback(any(), any(), any());
    }

    @Test
    @DisplayName("SET_TZ_CUSTOM маршрутизируется в stateResolver.resolve, а не в menuCallbackService")
    void should_route_to_state_resolver_when_callback_data_is_set_tz_custom() {
        // arrange
        User user = new User();
        when(userService.findOrCreate(eq(CHAT_ID), any())).thenReturn(user);
        Update update = callbackUpdate("SET_TZ_CUSTOM");

        // act
        bot.consume(update);

        // assert
        verify(stateResolver).resolve(eq(user), eq(update), any());
        verify(menuCallbackService, never()).handleCallback(any(), any(), any());
    }

    /**
     * Конструирует {@link Update} с callback-query: data + chatId, как присылает Telegram.
     * getFrom() намеренно не стабится (вернёт null) — getUserName() это переживает.
     */
    private Update callbackUpdate(String data) {
        Update update = org.mockito.Mockito.mock(Update.class);
        CallbackQuery callbackQuery = org.mockito.Mockito.mock(CallbackQuery.class);
        MaybeInaccessibleMessage message = org.mockito.Mockito.mock(MaybeInaccessibleMessage.class);

        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.getCallbackQuery()).thenReturn(callbackQuery);
        when(callbackQuery.getData()).thenReturn(data);
        when(callbackQuery.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(CHAT_ID);

        return update;
    }
}

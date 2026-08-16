package com.plantcare.bot.beans;

import com.plantcare.bot.command.CommandContainer;
import com.plantcare.bot.command.impl.CancelCommand;
import com.plantcare.bot.command.interfaces.BotCommand;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.bot.service.DiseaseMenuService;
import com.plantcare.bot.service.MenuCallbackService;
import com.plantcare.bot.service.NotificationCallbackService;
import com.plantcare.bot.service.NotificationDigestCallbackService;
import com.plantcare.bot.service.TransplantSuggestionCallbackService;
import com.plantcare.core.service.UserService;
import com.plantcare.bot.state.StateResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Branch-тесты диспетчера {@link PlantsCareBot} за пределами SET_TZ-регрессии
 * {@link PlantsCareBotTest} (issue #116). Покрывает: маршрутизацию всех
 * callback-префиксов, ветку "нет ни message ни callback" в {@code getChatId},
 * /cancel, диплинк /disease_&lt;id&gt; (валидный/невалидный id), IDLE vs
 * non-IDLE роутинг и устойчивость к исключению внутри {@code handleUpdate}
 * (единственная точка {@code Sentry.captureException} для poll-потока, issue #114).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PlantsCareBot dispatcher — branch-покрытие")
class PlantsCareBotBranchTest {

    private static final Long CHAT_ID = 4242L;

    @Mock private CommandContainer commandContainer;
    @Mock private UserService userService;
    @Mock private StateResolver stateResolver;
    @Mock private CancelCommand cancelCommand;
    @Mock private NotificationCallbackService notificationCallbackService;
    @Mock private NotificationDigestCallbackService notificationDigestCallbackService;
    @Mock private MenuCallbackService menuCallbackService;
    @Mock private DiseaseMenuService diseaseMenuService;
    @Mock private TransplantSuggestionCallbackService transplantSuggestionCallbackService;

    private PlantsCareBot bot;
    private User user;

    @BeforeEach
    void setUp() {
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

        user = new User();
        when(userService.findOrCreate(eq(CHAT_ID), any())).thenReturn(user);
    }

    // ===== getChatId: ни message, ни callbackQuery =====

    @Test
    @DisplayName("Апдейт без message и без callbackQuery: chatId=null → consume выходит сразу")
    void should_return_early_when_update_has_neither_message_nor_callback() {
        Update update = mock(Update.class);
        when(update.hasMessage()).thenReturn(false);
        when(update.hasCallbackQuery()).thenReturn(false);

        bot.consume(update);

        verifyNoInteractions(userService, commandContainer, stateResolver,
                notificationCallbackService, notificationDigestCallbackService,
                menuCallbackService, diseaseMenuService, transplantSuggestionCallbackService);
    }

    // ===== Роутинг callback-префиксов =====

    @Nested
    @DisplayName("Роутинг callback_data по префиксам")
    class CallbackRouting {

        @Test
        @DisplayName("digest: → notificationDigestCallbackService, остальные не тронуты")
        void should_route_digest_prefix_to_digest_service() {
            Update update = callbackUpdate("digest:done_all:500");

            bot.consume(update);

            verify(notificationDigestCallbackService).handleCallback(eq(update.getCallbackQuery()), any());
            verify(notificationCallbackService, never()).handleCallback(any(), any());
            verify(menuCallbackService, never()).handleCallback(any(), any(), any());
        }

        @Test
        @DisplayName("v1: → notificationCallbackService")
        void should_route_v1_prefix_to_notification_service() {
            Update update = callbackUpdate("v1:done:100");

            bot.consume(update);

            verify(notificationCallbackService).handleCallback(eq(update.getCallbackQuery()), any());
            verify(notificationDigestCallbackService, never()).handleCallback(any(), any());
        }

        @Test
        @DisplayName("TRANSPLANT_SUGGEST: → резолвит юзера и зовёт transplantSuggestionCallbackService")
        void should_route_transplant_suggest_prefix_with_resolved_user() {
            Update update = callbackUpdate("TRANSPLANT_SUGGEST:accept:5");

            bot.consume(update);

            verify(userService).findOrCreate(eq(CHAT_ID), any());
            verify(transplantSuggestionCallbackService)
                    .handleCallback(eq(update.getCallbackQuery()), any(), eq(user));
        }

        @Test
        @DisplayName("MENU: → резолвит юзера и зовёт menuCallbackService")
        void should_route_menu_prefix_to_menu_service() {
            Update update = callbackUpdate("MENU:PLANTS");

            bot.consume(update);

            verify(menuCallbackService).handleCallback(eq(update.getCallbackQuery()), any(), eq(user));
        }

        @Test
        @DisplayName("DISEASE: → тоже резолвится через menuCallbackService (один из OR-префиксов)")
        void should_route_disease_prefix_to_menu_service() {
            Update update = callbackUpdate("DISEASE:5");

            bot.consume(update);

            verify(menuCallbackService).handleCallback(eq(update.getCallbackQuery()), any(), eq(user));
        }

        @Test
        @DisplayName("PHOTO_PROGRESS: → тоже резолвится через menuCallbackService")
        void should_route_photo_progress_prefix_to_menu_service() {
            Update update = callbackUpdate("PHOTO_PROGRESS:ADD:10");

            bot.consume(update);

            verify(menuCallbackService).handleCallback(eq(update.getCallbackQuery()), any(), eq(user));
        }

        @Test
        @DisplayName("WEATHER: → тоже резолвится через menuCallbackService")
        void should_route_weather_prefix_to_menu_service() {
            Update update = callbackUpdate("WEATHER:refresh");

            bot.consume(update);

            verify(menuCallbackService).handleCallback(eq(update.getCallbackQuery()), any(), eq(user));
        }

        @Test
        @DisplayName("Неизвестный префикс: ни один callback-сервис не вызывается (только warn-лог)")
        void should_call_no_callback_service_when_prefix_unrecognized() {
            Update update = callbackUpdate("UNKNOWN_PREFIX:xyz");

            assertThatCode(() -> bot.consume(update)).doesNotThrowAnyException();

            verifyNoInteractions(notificationCallbackService, notificationDigestCallbackService,
                    menuCallbackService, transplantSuggestionCallbackService);
            verify(stateResolver, never()).resolve(any(), any(), any());
        }

        @Test
        @DisplayName("callback_data == null: пропускает все prefix-ветки, падает в обычный IDLE-флоу")
        void should_fall_through_to_idle_flow_when_callback_data_is_null() {
            CallbackQuery callbackQuery = mock(CallbackQuery.class);
            MaybeInaccessibleMessage message = mock(MaybeInaccessibleMessage.class);
            Update update = mock(Update.class);
            when(update.hasCallbackQuery()).thenReturn(true);
            when(update.getCallbackQuery()).thenReturn(callbackQuery);
            when(callbackQuery.getData()).thenReturn(null);
            when(callbackQuery.getMessage()).thenReturn(message);
            when(message.getChatId()).thenReturn(CHAT_ID);
            when(update.hasMessage()).thenReturn(false);

            bot.consume(update);

            // user резолвится (общий путь после callback-блока), но раз hasMessage()==false
            // и юзер IDLE — уходим по ранней "нет текста" ветке, ничего дальше не зовём.
            verify(userService).findOrCreate(eq(CHAT_ID), any());
            verifyNoInteractions(commandContainer, stateResolver);
        }
    }

    // ===== /cancel =====

    @Test
    @DisplayName("/cancel (регистронезависимо) вызывает cancelCommand.execute и не идёт дальше")
    void should_execute_cancel_command_when_text_is_cancel_case_insensitive() {
        Update update = messageUpdate("/CANCEL");

        bot.consume(update);

        verify(cancelCommand).execute(eq(update), any());
        verify(commandContainer, never()).retrieveCommand(any());
        verify(stateResolver, never()).resolve(any(), any(), any());
    }

    // ===== /disease_<id> deep-link =====

    @Nested
    @DisplayName("/disease_<id> диплинк (issue #73/#140)")
    class DiseaseDeepLink {

        @Test
        @DisplayName("Валидный числовой id → diseaseMenuService.sendCard с распарсенным id")
        void should_send_disease_card_when_id_is_valid_number() {
            Update update = messageUpdate("/disease_42");

            bot.consume(update);

            verify(diseaseMenuService).sendCard(eq(user), eq(42L), any());
        }

        @Test
        @DisplayName("Невалидный id (не число) → NumberFormatException перехвачен, sendCard не вызывается")
        void should_not_send_disease_card_and_not_throw_when_id_is_not_a_number() {
            Update update = messageUpdate("/disease_abc");

            assertThatCode(() -> bot.consume(update)).doesNotThrowAnyException();

            verify(diseaseMenuService, never()).sendCard(any(), any(), any());
        }

        @Test
        @DisplayName("Обычное сообщение без /disease_ префикса не триггерит диплинк-ветку")
        void should_not_trigger_disease_branch_for_regular_message() {
            BotCommand fallbackCommand = mock(BotCommand.class);
            when(commandContainer.retrieveCommand("привет")).thenReturn(fallbackCommand);
            Update update = messageUpdate("привет");

            bot.consume(update);

            verify(diseaseMenuService, never()).sendCard(any(), any(), any());
            verify(fallbackCommand).execute(eq(update), any());
        }
    }

    // ===== IDLE vs non-IDLE роутинг =====

    @Nested
    @DisplayName("Роутинг по состоянию беседы")
    class ConversationStateRouting {

        @Test
        @DisplayName("IDLE + текстовая команда → commandContainer.retrieveCommand().execute()")
        void should_retrieve_and_execute_command_when_idle_with_text() {
            BotCommand plantsCommand = mock(BotCommand.class);
            when(commandContainer.retrieveCommand("/plants")).thenReturn(plantsCommand);
            Update update = messageUpdate("/plants");

            bot.consume(update);

            verify(plantsCommand).execute(eq(update), any());
            verify(stateResolver, never()).resolve(any(), any(), any());
        }

        @Test
        @DisplayName("IDLE без текста → ранний return, ни commandContainer, ни stateResolver не вызываются")
        void should_return_early_when_idle_and_message_has_no_text() {
            Update update = mock(Update.class);
            Message message = mock(Message.class);
            when(update.hasCallbackQuery()).thenReturn(false);
            when(update.hasMessage()).thenReturn(true);
            when(update.getMessage()).thenReturn(message);
            when(message.getChatId()).thenReturn(CHAT_ID);
            when(message.hasText()).thenReturn(false);

            bot.consume(update);

            verifyNoInteractions(commandContainer, stateResolver);
        }

        @Test
        @DisplayName("non-IDLE состояние → stateResolver.resolve, commandContainer не вызывается")
        void should_delegate_to_state_resolver_when_not_idle() {
            User awaitingUser = new User();
            awaitingUser.setConversationState(ConversationState.AWAITING_PLANT_NAME);
            when(userService.findOrCreate(eq(CHAT_ID), any())).thenReturn(awaitingUser);
            Update update = messageUpdate("Монстера");

            bot.consume(update);

            verify(stateResolver).resolve(eq(awaitingUser), eq(update), any());
            verify(commandContainer, never()).retrieveCommand(any());
        }
    }

    // ===== Устойчивость к исключению (issue #114) =====

    @Test
    @DisplayName("Исключение внутри handleUpdate перехватывается — consume не бросает наружу")
    void should_not_propagate_exception_when_handling_throws() {
        when(userService.findOrCreate(eq(CHAT_ID), any()))
                .thenThrow(new RuntimeException("db exploded"));
        Update update = messageUpdate("/plants");

        assertThatCode(() -> bot.consume(update)).doesNotThrowAnyException();
    }

    // ===== helpers =====

    private Update callbackUpdate(String data) {
        Update update = mock(Update.class);
        CallbackQuery callbackQuery = mock(CallbackQuery.class);
        MaybeInaccessibleMessage message = mock(MaybeInaccessibleMessage.class);

        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.getCallbackQuery()).thenReturn(callbackQuery);
        when(callbackQuery.getData()).thenReturn(data);
        when(callbackQuery.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(CHAT_ID);

        return update;
    }

    private Update messageUpdate(String text) {
        Update update = mock(Update.class);
        Message message = mock(Message.class);

        when(update.hasCallbackQuery()).thenReturn(false);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(CHAT_ID);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn(text);

        return update;
    }
}

package com.plantcare.bot.state.impl;

import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit-тесты для AwaitingPlantWateringIntervalStateHandler")
class AwaitingPlantWateringIntervalStateHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private PlantService plantService;

    @Mock
    private TelegramClient client;

    private AwaitingPlantWateringIntervalStateHandler handler;
    private User user;

    private final Long chatId = 888L;

    @BeforeEach
    void setUp() {
        handler = new AwaitingPlantWateringIntervalStateHandler(userService, plantService);

        user = User.builder()
                .telegramChatId(chatId)
                .build();
    }

    @Test
    @DisplayName("Поддерживает состояние AWAITING_PLANT_WATERING_INTERVAL")
    void shouldSupportState() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_PLANT_WATERING_INTERVAL);
    }

    @Test
    @DisplayName("CONFIRM_TEMPLATE переводит к вводу имени растения")
    void shouldConfirmTemplateAndProceedToName() throws TelegramApiException {
        Update update = callbackUpdate("CONFIRM_TEMPLATE");

        handler.handle(user, update, client);

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_NAME);
        verify(client).execute(any(AnswerCallbackQuery.class));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Как назовём твоё растение?");
    }

    @Test
    @DisplayName("EDIT_INTERVAL просит ввести свой интервал")
    void shouldAskForCustomInterval() throws TelegramApiException {
        Update update = callbackUpdate("EDIT_INTERVAL");

        handler.handle(user, update, client);

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_WATERING_INTERVAL);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Введи интервал полива");
    }

    @Test
    @DisplayName("BACK_TO_SPECIES возвращает к выбору видов с клавиатурой")
    void shouldGoBackToSpeciesChoice() throws TelegramApiException {
        Species species = Species.builder().name("Монстера").build();
        ReflectionTestUtils.setField(species, "id", 1L);
        when(plantService.getPopularSpecies(6)).thenReturn(List.of(species));

        Update update = callbackUpdate("BACK_TO_SPECIES");

        handler.handle(user, update, client);

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_SPECIES_CHOICE);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("популярные виды");
    }

    @Test
    @DisplayName("Неизвестный callback всё равно отвечает на callback query без сообщения")
    void shouldAnswerCallbackEvenWhenUnknownData() throws TelegramApiException {
        Update update = callbackUpdate("UNKNOWN_DATA");

        handler.handle(user, update, client);

        verify(client).execute(any(AnswerCallbackQuery.class));
        verify(client, never()).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("Callback от чужого пользователя игнорируется")
    void shouldIgnoreCallbackFromAnotherUser() {
        Update update = callbackUpdateFromDifferentUser("CONFIRM_TEMPLATE");

        handler.handle(user, update, client);

        verifyNoInteractions(client, userService, plantService);
    }

    @Test
    @DisplayName("Валидный интервал сохраняется и ведёт к вводу имени")
    void shouldAcceptValidInterval() throws TelegramApiException {
        Update update = textUpdate("10");

        handler.handle(user, update, client);

        verify(userService).setStateData(user, "interval_days", "10");
        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_NAME);
    }

    @Test
    @DisplayName("Интервал вне диапазона отправляет ошибку валидации")
    void shouldRejectOutOfRangeInterval() throws TelegramApiException {
        Update update = textUpdate("400");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Интервал должен быть от 1 до 365 дней");

        verify(userService, never()).setStateData(any(), any(), any());
    }

    @Test
    @DisplayName("Нечисловой текст отправляет ошибку парсинга")
    void shouldRejectNonNumericInput() throws TelegramApiException {
        Update update = textUpdate("не число");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Введи число от 1 до 365");
    }

    @Test
    @DisplayName("Update без сообщения и без callback игнорируется")
    void shouldIgnoreUpdateWithNeitherMessageNorCallback() {
        Update update = new Update();

        handler.handle(user, update, client);

        verifyNoInteractions(client, userService, plantService);
    }

    @Test
    @DisplayName("TelegramApiException на подтверждении шаблона не валит хендлер")
    void shouldSwallowTelegramApiExceptionOnConfirm() throws TelegramApiException {
        doThrow(new TelegramApiException("boom")).when(client).execute(any(SendMessage.class));
        Update update = callbackUpdate("CONFIRM_TEMPLATE");

        handler.handle(user, update, client);

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_NAME);
    }

    @Test
    @DisplayName("Ошибка ответа на callback не мешает продолжить обработку")
    void shouldContinueWhenAnswerCallbackFails() throws TelegramApiException {
        doThrow(new RuntimeException("callback failure")).when(client).execute(any(AnswerCallbackQuery.class));
        Update update = callbackUpdate("CONFIRM_TEMPLATE");

        handler.handle(user, update, client);

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_NAME);
    }

    private Update textUpdate(String text) {
        Update update = new Update();
        Message message = new Message();
        message.setText(text);
        update.setMessage(message);
        return update;
    }

    private Update callbackUpdate(String data) {
        Update update = new Update();
        CallbackQuery callbackQuery = new CallbackQuery();
        callbackQuery.setId("cb-id");
        callbackQuery.setData(data);
        callbackQuery.setMessage(new Message());
        callbackQuery.setFrom(org.telegram.telegrambots.meta.api.objects.User.builder()
                .id(chatId)
                .firstName("test")
                .isBot(false)
                .build());
        update.setCallbackQuery(callbackQuery);
        return update;
    }

    private Update callbackUpdateFromDifferentUser(String data) {
        Update update = new Update();
        CallbackQuery callbackQuery = new CallbackQuery();
        callbackQuery.setId("cb-id");
        callbackQuery.setData(data);
        callbackQuery.setMessage(new Message());
        callbackQuery.setFrom(org.telegram.telegrambots.meta.api.objects.User.builder()
                .id(chatId + 1)
                .firstName("other")
                .isBot(false)
                .build());
        update.setCallbackQuery(callbackQuery);
        return update;
    }
}

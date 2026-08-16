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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit-тесты для AwaitingPlantSpeciesSearchStateHandler")
class AwaitingPlantSpeciesSearchStateHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private PlantService plantService;

    @Mock
    private TelegramClient client;

    private AwaitingPlantSpeciesSearchStateHandler handler;
    private User user;

    private final Long chatId = 777L;

    @BeforeEach
    void setUp() {
        handler = new AwaitingPlantSpeciesSearchStateHandler(userService, plantService);

        user = User.builder()
                .telegramChatId(chatId)
                .build();
    }

    @Test
    @DisplayName("Поддерживает состояние AWAITING_PLANT_SPECIES_SEARCH")
    void shouldSupportState() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_PLANT_SPECIES_SEARCH);
    }

    @Test
    @DisplayName("SEARCH_SELECT с найденным видом сохраняет интервал и показывает превью")
    void shouldSelectSpeciesFromSearch() throws TelegramApiException {
        Species species = Species.builder().name("Монстера").wateringDays(5).build();
        ReflectionTestUtils.setField(species, "id", 10L);
        when(plantService.getSpeciesById(10L)).thenReturn(Optional.of(species));

        Update update = callbackUpdate("SEARCH_SELECT:10");

        handler.handle(user, update, client);

        verify(userService).setStateData(user, "species_id", "10");
        verify(userService).setStateData(user, "interval_days", "5");
        verify(client).execute(any(AnswerCallbackQuery.class));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Монстера");
    }

    @Test
    @DisplayName("SEARCH_SELECT без wateringDays у вида использует дефолт 7")
    void shouldDefaultIntervalWhenWateringDaysMissing() throws TelegramApiException {
        Species species = Species.builder().name("Кактус").wateringDays(null).build();
        ReflectionTestUtils.setField(species, "id", 11L);
        when(plantService.getSpeciesById(11L)).thenReturn(Optional.of(species));

        Update update = callbackUpdate("SEARCH_SELECT:11");

        handler.handle(user, update, client);

        verify(userService).setStateData(user, "interval_days", "7");
    }

    @Test
    @DisplayName("SEARCH_SELECT с несуществующим видом отправляет ошибку")
    void shouldSendErrorWhenSpeciesNotFound() throws TelegramApiException {
        when(plantService.getSpeciesById(99L)).thenReturn(Optional.empty());

        Update update = callbackUpdate("SEARCH_SELECT:99");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Вид не найден");

        verify(userService, never()).setStateData(any(), eq("interval_days"), any());
    }

    @Test
    @DisplayName("BACK_TO_SPECIES возвращает к выбору популярных видов")
    void shouldGoBackToSpeciesChoice() throws TelegramApiException {
        Update update = callbackUpdate("BACK_TO_SPECIES");

        handler.handle(user, update, client);

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_SPECIES_CHOICE);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Выбери вид растения");
    }

    @Test
    @DisplayName("CONFIRM_TEMPLATE переводит к вводу имени растения")
    void shouldConfirmTemplateAndAskName() throws TelegramApiException {
        Update update = callbackUpdate("CONFIRM_TEMPLATE");

        handler.handle(user, update, client);

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_NAME);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Как назовём твоё растение?");
    }

    @Test
    @DisplayName("EDIT_INTERVAL переводит к ручному вводу интервала")
    void shouldEditIntervalAndAskForInput() throws TelegramApiException {
        Update update = callbackUpdate("EDIT_INTERVAL");

        handler.handle(user, update, client);

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_WATERING_INTERVAL);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Введи интервал полива");
    }

    @Test
    @DisplayName("Callback от чужого пользователя игнорируется")
    void shouldIgnoreCallbackFromAnotherUser() throws TelegramApiException {
        Update update = callbackUpdateFromDifferentUser("SEARCH_SELECT:10");

        handler.handle(user, update, client);

        verify(client, never()).execute(any(SendMessage.class));
        verifyNoInteractions(plantService);
    }

    @Test
    @DisplayName("Пустой текст запроса просит ввести название")
    void shouldPromptWhenQueryBlank() throws TelegramApiException {
        Update update = textUpdate("   ");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Введи название растения");

        verifyNoInteractions(plantService);
    }

    @Test
    @DisplayName("Поиск без результатов показывает подсказку и кнопку назад")
    void shouldShowNoResultsMessage() throws TelegramApiException {
        when(plantService.searchSpecies("хзчто", 10)).thenReturn(List.of());

        Update update = textUpdate("хзчто");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Ничего не найдено").contains("хзчто");
        assertThat(captor.getValue().getReplyMarkup()).isNotNull();
    }

    @Test
    @DisplayName("Поиск с результатами показывает клавиатуру выбора")
    void shouldShowSearchResults() throws TelegramApiException {
        Species s1 = Species.builder().name("Монстера").build();
        ReflectionTestUtils.setField(s1, "id", 1L);
        Species s2 = Species.builder().name("Фикус").build();
        ReflectionTestUtils.setField(s2, "id", 2L);

        when(plantService.searchSpecies("мон", 10)).thenReturn(List.of(s1, s2));

        Update update = textUpdate("мон");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Найдено растений: 2");
    }

    @Test
    @DisplayName("Update без сообщения и без callback игнорируется")
    void shouldIgnoreUpdateWithNeitherMessageNorCallback() {
        Update update = new Update();

        handler.handle(user, update, client);

        verifyNoInteractions(plantService, userService, client);
    }

    @Test
    @DisplayName("TelegramApiException при отправке результатов поиска не валит хендлер")
    void shouldSwallowTelegramApiExceptionOnSearchResults() throws TelegramApiException {
        when(plantService.searchSpecies("мон", 10)).thenReturn(List.of());
        doThrow(new TelegramApiException("boom")).when(client).execute(any(SendMessage.class));

        Update update = textUpdate("мон");

        handler.handle(user, update, client);

        verify(client).execute(any(SendMessage.class));
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
        org.telegram.telegrambots.meta.api.objects.User from =
                org.telegram.telegrambots.meta.api.objects.User.builder()
                        .id(chatId)
                        .firstName("test")
                        .isBot(false)
                        .build();
        callbackQuery.setFrom(from);
        update.setCallbackQuery(callbackQuery);
        return update;
    }

    private Update callbackUpdateFromDifferentUser(String data) {
        Update update = new Update();
        CallbackQuery callbackQuery = new CallbackQuery();
        callbackQuery.setId("cb-id");
        callbackQuery.setData(data);
        callbackQuery.setMessage(new Message());
        org.telegram.telegrambots.meta.api.objects.User from =
                org.telegram.telegrambots.meta.api.objects.User.builder()
                        .id(chatId + 1)
                        .firstName("other")
                        .isBot(false)
                        .build();
        callbackQuery.setFrom(from);
        update.setCallbackQuery(callbackQuery);
        return update;
    }
}

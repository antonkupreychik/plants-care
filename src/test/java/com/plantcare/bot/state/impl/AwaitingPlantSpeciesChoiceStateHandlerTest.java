package com.plantcare.bot.state.impl;

import com.plantcare.core.domain.PlantTemplate;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.PlantTemplateService;
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
@DisplayName("Unit-тесты для AwaitingPlantSpeciesChoiceStateHandler")
class AwaitingPlantSpeciesChoiceStateHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private PlantService plantService;

    @Mock
    private PlantTemplateService plantTemplateService;

    @Mock
    private TelegramClient client;

    private AwaitingPlantSpeciesChoiceStateHandler handler;
    private User user;

    private final Long chatId = 321L;
    private final Long userId = 4L;

    @BeforeEach
    void setUp() {
        handler = new AwaitingPlantSpeciesChoiceStateHandler(userService, plantService, plantTemplateService);

        user = User.builder()
                .telegramChatId(chatId)
                .build();
        ReflectionTestUtils.setField(user, "id", userId);
    }

    @Test
    @DisplayName("Поддерживает состояние AWAITING_PLANT_SPECIES_CHOICE")
    void shouldSupportState() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_PLANT_SPECIES_CHOICE);
    }

    @Test
    @DisplayName("SPECIES:CUSTOM выбирает растение без шаблона")
    void shouldSelectCustomWithoutTemplate() throws TelegramApiException {
        Update update = callbackUpdate("SPECIES:CUSTOM");

        handler.handle(user, update, client);

        verify(userService).setStateData(user, "species_id", "null");
        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_WATERING_INTERVAL);
        verify(client).execute(any(AnswerCallbackQuery.class));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Вводи интервал полива");
    }

    @Test
    @DisplayName("SPECIES:SEARCH переводит к поиску вида")
    void shouldGoToSearch() throws TelegramApiException {
        Update update = callbackUpdate("SPECIES:SEARCH");

        handler.handle(user, update, client);

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_SPECIES_SEARCH);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Напиши название растения");
    }

    @Test
    @DisplayName("SPECIES:<id> выбирает конкретный вид и показывает превью")
    void shouldSelectSpeciesById() throws TelegramApiException {
        Species species = Species.builder().name("Монстера").wateringDays(5).build();
        ReflectionTestUtils.setField(species, "id", 15L);
        when(plantService.getSpeciesById(15L)).thenReturn(Optional.of(species));

        Update update = callbackUpdate("SPECIES:15");

        handler.handle(user, update, client);

        verify(userService).setStateData(user, "species_id", "15");
        verify(userService).setStateData(user, "interval_days", "5");
        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_WATERING_INTERVAL);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Монстера");
    }

    @Test
    @DisplayName("SPECIES:<id> без wateringDays использует дефолтный интервал 7")
    void shouldDefaultIntervalWhenSpeciesHasNoWateringDays() throws TelegramApiException {
        Species species = Species.builder().name("Кактус").wateringDays(null).build();
        ReflectionTestUtils.setField(species, "id", 16L);
        when(plantService.getSpeciesById(16L)).thenReturn(Optional.of(species));

        Update update = callbackUpdate("SPECIES:16");

        handler.handle(user, update, client);

        verify(userService).setStateData(user, "interval_days", "7");
    }

    @Test
    @DisplayName("SPECIES:<id> с несуществующим видом отправляет ошибку")
    void shouldSendErrorWhenSpeciesIdNotFound() throws TelegramApiException {
        when(plantService.getSpeciesById(77L)).thenReturn(Optional.empty());

        Update update = callbackUpdate("SPECIES:77");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Вид не найден");
    }

    @Test
    @DisplayName("SPECIES:<не число> отправляет ошибку без падения")
    void shouldSendErrorWhenSpeciesIdMalformed() throws TelegramApiException {
        Update update = callbackUpdate("SPECIES:abc");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Вид не найден");

        verifyNoInteractions(plantService);
    }

    @Test
    @DisplayName("SPECIES:MY_TEMPLATES без шаблонов показывает пустое сообщение")
    void shouldShowEmptyTemplatesMessage() throws TelegramApiException {
        when(plantTemplateService.getUserTemplates(userId)).thenReturn(List.of());

        Update update = callbackUpdate("SPECIES:MY_TEMPLATES");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Пока нет шаблонов");
    }

    @Test
    @DisplayName("SPECIES:MY_TEMPLATES с шаблонами показывает список")
    void shouldShowTemplatesList() throws TelegramApiException {
        PlantTemplate template = PlantTemplate.builder().userId(userId).name("Мой шаблон").build();
        ReflectionTestUtils.setField(template, "id", 5L);
        when(plantTemplateService.getUserTemplates(userId)).thenReturn(List.of(template));

        Update update = callbackUpdate("SPECIES:MY_TEMPLATES");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Мои шаблоны");
        assertThat(captor.getValue().getReplyMarkup()).isNotNull();
    }

    @Test
    @DisplayName("TPL_PICK с валидным шаблоном переводит к вводу имени")
    void shouldPickTemplateAndAskForName() throws TelegramApiException {
        PlantTemplate template = PlantTemplate.builder().userId(userId).name("Мой шаблон").build();
        ReflectionTestUtils.setField(template, "id", 5L);
        when(plantTemplateService.getTemplate(userId, 5L)).thenReturn(Optional.of(template));

        Update update = callbackUpdate("TPL_PICK:5");

        handler.handle(user, update, client);

        verify(userService).setStateData(user, "template_id", "5");
        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_NAME_FROM_TEMPLATE);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Как назвать новое растение?");
    }

    @Test
    @DisplayName("TPL_PICK с отсутствующим шаблоном отправляет ошибку")
    void shouldSendErrorWhenTemplateMissing() throws TelegramApiException {
        when(plantTemplateService.getTemplate(userId, 9L)).thenReturn(Optional.empty());

        Update update = callbackUpdate("TPL_PICK:9");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Вид не найден");

        verify(userService, never()).updateState(user, ConversationState.AWAITING_PLANT_NAME_FROM_TEMPLATE);
    }

    @Test
    @DisplayName("TPL_PICK с некорректным id отправляет ошибку без падения")
    void shouldSendErrorWhenTemplateIdMalformed() throws TelegramApiException {
        Update update = callbackUpdate("TPL_PICK:xyz");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Вид не найден");

        verifyNoInteractions(plantTemplateService);
    }

    @Test
    @DisplayName("Callback от чужого пользователя игнорируется")
    void shouldIgnoreCallbackFromAnotherUser() {
        Update update = callbackUpdateFromDifferentUser("SPECIES:CUSTOM");

        handler.handle(user, update, client);

        verifyNoInteractions(client, userService, plantService, plantTemplateService);
    }

    @Test
    @DisplayName("Update без callback игнорируется")
    void shouldIgnoreUpdateWithoutCallback() {
        Update update = new Update();

        handler.handle(user, update, client);

        verifyNoInteractions(client, userService, plantService, plantTemplateService);
    }

    @Test
    @DisplayName("TelegramApiException при отправке сообщения не валит хендлер")
    void shouldSwallowTelegramApiException() throws TelegramApiException {
        doThrow(new TelegramApiException("boom")).when(client).execute(any(SendMessage.class));
        Update update = callbackUpdate("SPECIES:CUSTOM");

        handler.handle(user, update, client);

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_WATERING_INTERVAL);
    }

    @Test
    @DisplayName("Ошибка ответа на callback не мешает продолжить обработку")
    void shouldContinueWhenAnswerCallbackFails() throws TelegramApiException {
        doThrow(new RuntimeException("callback failure")).when(client).execute(any(AnswerCallbackQuery.class));
        Update update = callbackUpdate("SPECIES:CUSTOM");

        handler.handle(user, update, client);

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_WATERING_INTERVAL);
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

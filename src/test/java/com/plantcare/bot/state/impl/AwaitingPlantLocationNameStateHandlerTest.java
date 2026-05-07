package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.LocationService;
import com.plantcare.bot.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit-тесты для AwaitingPlantLocationNameStateHandler")
class AwaitingPlantLocationNameStateHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private LocationService locationService;

    @Mock
    private TelegramClient client;

    @InjectMocks
    private AwaitingPlantLocationNameStateHandler handler;

    private User user;

    private final Long userId = 1L;
    private final Long chatId = 123L;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(chatId)
                .build();

        setPrivateField(user, "id", userId);
    }

    @Test
    @DisplayName("Возвращает корректное поддерживаемое состояние")
    void shouldReturnSupportedState() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_PLANT_LOCATION_NAME);
    }

    @Test
    @DisplayName("Update без message — просит ввести название комнаты текстом")
    void shouldAskForTextWhenUpdateHasNoMessage() throws Exception {
        Update update = new Update();

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Введи название комнаты текстом");

        verifyNoInteractions(userService);
        verifyNoInteractions(locationService);
    }

    @Test
    @DisplayName("Message без текста — просит ввести название комнаты текстом")
    void shouldAskForTextWhenMessageHasNoText() throws Exception {
        Update update = new Update();
        Message message = new Message();
        update.setMessage(message);

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Введи название комнаты текстом");

        verifyNoInteractions(userService);
        verifyNoInteractions(locationService);
    }

    @Test
    @DisplayName("Пустое название комнаты — отправляет ошибку")
    void shouldSendErrorWhenNameIsBlank() throws Exception {
        Update update = textUpdate("   ");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Название комнаты должно быть от 1 до 30 символов");

        verifyNoInteractions(userService);
        verifyNoInteractions(locationService);
    }

    @Test
    @DisplayName("Название больше 30 символов — отправляет ошибку")
    void shouldSendErrorWhenNameIsTooLong() throws Exception {
        Update update = textUpdate("ОченьОченьОченьОченьДлинноеНазваниеКомнаты");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Название комнаты должно быть от 1 до 30 символов");

        verifyNoInteractions(userService);
        verifyNoInteractions(locationService);
    }

    @Test
    @DisplayName("Если достигнут лимит комнат — отправляет ошибку и возвращает в AWAITING_PLANT_ROOM")
    void shouldSendErrorAndReturnToRoomChoiceWhenLimitReached() throws Exception {
        Update update = textUpdate("Кухня");

        when(locationService.hasReachedLocationsLimit(userId))
                .thenReturn(true);
        when(locationService.getMaxLocationsPerUser())
                .thenReturn(5);

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Можно создать максимум")
                .contains("5")
                .contains("комнат");

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_ROOM);
        verify(userService, never()).setStateData(any(), any(), any());
    }

    @Test
    @DisplayName("Если комната с таким названием уже есть — отправляет ошибку")
    void shouldSendErrorWhenDuplicateLocationExists() throws Exception {
        Update update = textUpdate("кухня");

        Location existingLocation = Location.builder()
                .name("Кухня")
                .emoji("🍳")
                .build();

        when(locationService.hasReachedLocationsLimit(userId))
                .thenReturn(false);
        when(locationService.getUserLocations(userId))
                .thenReturn(List.of(existingLocation));

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Такая комната уже есть");

        verify(userService, never()).setStateData(any(), any(), any());
        verify(userService, never()).updateState(user, ConversationState.AWAITING_PLANT_LOCATION_EMOJI);
    }

    @Test
    @DisplayName("Корректное название — сохраняет название, переводит в выбор emoji и отправляет клавиатуру")
    void shouldSaveNameUpdateStateAndAskEmojiWhenNameIsValid() throws Exception {
        Update update = textUpdate("Кухня");

        when(locationService.hasReachedLocationsLimit(userId))
                .thenReturn(false);
        when(locationService.getUserLocations(userId))
                .thenReturn(List.of());

        handler.handle(user, update, client);

        verify(userService).setStateData(user, "new_plant_location_name", "Кухня");
        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_LOCATION_EMOJI);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        SendMessage message = captor.getValue();

        assertThat(message.getText())
                .contains("Выбери emoji для комнаты")
                .contains("отправь свой одним сообщением");

        assertThat(message.getReplyMarkup())
                .isInstanceOf(InlineKeyboardMarkup.class);
    }

    @Test
    @DisplayName("Название обрезается trim перед сохранением")
    void shouldTrimNameBeforeSave() throws Exception {
        Update update = textUpdate("   Балкон   ");

        when(locationService.hasReachedLocationsLimit(userId))
                .thenReturn(false);
        when(locationService.getUserLocations(userId))
                .thenReturn(List.of());

        handler.handle(user, update, client);

        verify(userService).setStateData(user, "new_plant_location_name", "Балкон");
        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_LOCATION_EMOJI);
    }

    @Test
    @DisplayName("TelegramApiException при отправке вопроса про emoji — не валит handler")
    void shouldHandleTelegramApiExceptionWhenAskEmojiFails() throws Exception {
        Update update = textUpdate("Кухня");

        when(locationService.hasReachedLocationsLimit(userId))
                .thenReturn(false);
        when(locationService.getUserLocations(userId))
                .thenReturn(List.of());

        doThrow(new TelegramApiException("API error"))
                .when(client)
                .execute(any(SendMessage.class));

        handler.handle(user, update, client);

        verify(userService).setStateData(user, "new_plant_location_name", "Кухня");
        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_LOCATION_EMOJI);
    }

    private Update textUpdate(String text) {
        Update update = new Update();

        Message message = new Message();
        message.setText(text);

        update.setMessage(message);

        return update;
    }

    private void setPrivateField(Object target, String fieldName, Object value) {
        try {
            Class<?> clazz = target.getClass();
            Field field = null;

            while (clazz != null) {
                try {
                    field = clazz.getDeclaredField(fieldName);
                    break;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }

            if (field == null) {
                throw new RuntimeException("Field not found: " + fieldName);
            }

            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }
}
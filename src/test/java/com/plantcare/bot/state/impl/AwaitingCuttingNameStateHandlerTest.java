package com.plantcare.bot.state.impl;

import com.plantcare.bot.service.PlantCardService;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для AwaitingCuttingNameStateHandler")
class AwaitingCuttingNameStateHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private PlantCardService plantCardService;

    @Mock
    private TelegramClient telegramClient;

    @InjectMocks
    private AwaitingCuttingNameStateHandler handler;

    private User user;

    @BeforeEach
    void setUp() {
        Map<String, Object> stateData = new HashMap<>();
        stateData.put("parent_plant_id", "7");

        user = User.builder()
                .telegramChatId(200L)
                .stateData(stateData)
                .build();
        ReflectionTestUtils.setField(user, "id", 1L);
    }

    @Test
    @DisplayName("Поддерживает состояние AWAITING_CUTTING_NAME")
    void shouldSupportExpectedState() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_CUTTING_NAME);
    }

    @Test
    @DisplayName("Создаёт черенок и показывает карточку при валидном имени")
    void shouldCreateCuttingAndFinishCreation() throws TelegramApiException {
        Update update = textUpdate("Отросток монстеры");
        Plant cutting = Plant.builder().name("Отросток монстеры").build();
        ReflectionTestUtils.setField(cutting, "id", 15L);

        when(plantCardService.createCutting(user, 7L, "Отросток монстеры")).thenReturn(cutting);

        handler.handle(user, update, telegramClient);

        verify(plantCardService).finishPlantCreation(user, cutting, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Черенок");
        assertThat(captor.getValue().getText()).contains("Отросток монстеры");
        assertThat(captor.getValue().getText()).contains("добавлен");
    }

    @Test
    @DisplayName("Просит ввести имя, если апдейт без текста")
    void shouldPromptWhenUpdateHasNoText() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasMessage()).thenReturn(false);

        handler.handle(user, update, telegramClient);

        verifyNoInteractions(plantCardService, userService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Введи имя нового растения");
    }

    @Test
    @DisplayName("Отклоняет пустое (после trim) имя")
    void shouldRejectBlankName() throws TelegramApiException {
        Update update = textUpdate("   ");

        handler.handle(user, update, telegramClient);

        verifyNoInteractions(plantCardService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("от 1 до 100 символов");
    }

    @Test
    @DisplayName("Отклоняет слишком длинное имя")
    void shouldRejectTooLongName() throws TelegramApiException {
        Update update = textUpdate("б".repeat(150));

        handler.handle(user, update, telegramClient);

        verifyNoInteractions(plantCardService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("от 1 до 100 символов");
    }

    @Test
    @DisplayName("Сбрасывает в IDLE, если parent_plant_id отсутствует в stateData")
    void shouldResetToIdleWhenParentIdMissing() throws TelegramApiException {
        user.setStateData(new HashMap<>());
        Update update = textUpdate("Отросток");

        handler.handle(user, update, telegramClient);

        verify(userService).resetToIdle(user);
        verifyNoInteractions(plantCardService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Контекст утерян");
    }

    @Test
    @DisplayName("Сбрасывает в IDLE, если parent_plant_id не парсится как число")
    void shouldResetToIdleWhenParentIdIsNotNumeric() throws TelegramApiException {
        user.getStateData().put("parent_plant_id", "abc");
        Update update = textUpdate("Отросток");

        handler.handle(user, update, telegramClient);

        verify(userService).resetToIdle(user);
        verifyNoInteractions(plantCardService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Контекст создания повреждён");
    }

    @Test
    @DisplayName("Сбрасывает в IDLE и показывает ошибку, если создание черенка бросает IllegalArgumentException")
    void shouldResetToIdleWhenCreationFails() throws TelegramApiException {
        Update update = textUpdate("Отросток");
        when(plantCardService.createCutting(user, 7L, "Отросток"))
                .thenThrow(new IllegalArgumentException("Родительское растение не найдено"));

        handler.handle(user, update, telegramClient);

        verify(userService).resetToIdle(user);
        verify(plantCardService, never()).finishPlantCreation(any(), any(), any());

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Родительское растение не найдено");
    }

    @Test
    @DisplayName("Не бросает исключение наружу, если отправка подсказки падает")
    void shouldNotThrowWhenHintSendFails() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasMessage()).thenReturn(false);
        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiException("boom"));

        handler.handle(user, update, telegramClient);

        verify(telegramClient).execute(any(SendMessage.class));
    }

    private Update textUpdate(String text) {
        Update update = mock(Update.class);
        Message message = mock(Message.class);

        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn(text);

        return update;
    }
}

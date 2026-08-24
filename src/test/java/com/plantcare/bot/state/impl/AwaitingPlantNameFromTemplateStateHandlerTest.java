package com.plantcare.bot.state.impl;

import com.plantcare.bot.service.PlantCardService;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.PlantTemplate;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.service.PlantTemplateService;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для AwaitingPlantNameFromTemplateStateHandler")
class AwaitingPlantNameFromTemplateStateHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private PlantTemplateService plantTemplateService;

    @Mock
    private PlantCardService plantCardService;

    @Mock
    private TelegramClient telegramClient;

    @InjectMocks
    private AwaitingPlantNameFromTemplateStateHandler handler;

    private User user;

    @BeforeEach
    void setUp() {
        Map<String, Object> stateData = new HashMap<>();
        stateData.put("template_id", "42");

        user = User.builder()
                .telegramChatId(100L)
                .stateData(stateData)
                .build();
        ReflectionTestUtils.setField(user, "id", 1L);
    }

    private PlantTemplate templateWithId(long id, String name) {
        PlantTemplate template = PlantTemplate.builder().name(name).build();
        ReflectionTestUtils.setField(template, "id", id);
        return template;
    }

    private Plant plantWithId(long id, String name) {
        Plant plant = Plant.builder().name(name).build();
        ReflectionTestUtils.setField(plant, "id", id);
        return plant;
    }

    @Test
    @DisplayName("Поддерживает состояние AWAITING_PLANT_NAME_FROM_TEMPLATE")
    void shouldSupportExpectedState() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_PLANT_NAME_FROM_TEMPLATE);
    }

    @Test
    @DisplayName("Создаёт растение из шаблона и показывает карточку при валидном имени")
    void shouldCreatePlantFromTemplateAndFinishCreation() throws TelegramApiException {
        Update update = textUpdate("Монстера");
        PlantTemplate template = templateWithId(42L, "Монстера базовая");
        Plant plant = plantWithId(9L, "Монстера");

        when(plantTemplateService.getTemplate(1L, 42L)).thenReturn(Optional.of(template));
        when(plantTemplateService.createPlantFromTemplate(user, 42L, "Монстера")).thenReturn(plant);

        handler.handle(user, update, telegramClient);

        verify(plantCardService).finishPlantCreation(user, plant, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Растение добавлено из шаблона");
        assertThat(captor.getValue().getText()).contains("Монстера базовая");
    }

    @Test
    @DisplayName("Просит ввести имя, если апдейт без текста")
    void shouldPromptWhenUpdateHasNoText() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasMessage()).thenReturn(false);

        handler.handle(user, update, telegramClient);

        verifyNoInteractions(plantTemplateService, plantCardService, userService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Введи имя нового растения");
    }

    @Test
    @DisplayName("Отклоняет пустое (после trim) имя растения")
    void shouldRejectBlankName() throws TelegramApiException {
        Update update = textUpdate("   ");

        handler.handle(user, update, telegramClient);

        verifyNoInteractions(plantTemplateService, plantCardService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("от 1 до 100 символов");
    }

    @Test
    @DisplayName("Отклоняет слишком длинное имя растения")
    void shouldRejectTooLongName() throws TelegramApiException {
        Update update = textUpdate("а".repeat(101));

        handler.handle(user, update, telegramClient);

        verifyNoInteractions(plantTemplateService, plantCardService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("от 1 до 100 символов");
    }

    @Test
    @DisplayName("Сбрасывает в IDLE и сообщает об утере контекста, если template_id отсутствует")
    void shouldResetToIdleWhenTemplateIdMissing() throws TelegramApiException {
        user.setStateData(new HashMap<>());
        Update update = textUpdate("Монстера");

        handler.handle(user, update, telegramClient);

        verify(userService).resetToIdle(user);
        verifyNoInteractions(plantTemplateService, plantCardService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Контекст утерян");
    }

    @Test
    @DisplayName("Сбрасывает в IDLE, если template_id не парсится как число")
    void shouldResetToIdleWhenTemplateIdIsNotNumeric() throws TelegramApiException {
        user.getStateData().put("template_id", "not-a-number");
        Update update = textUpdate("Монстера");

        handler.handle(user, update, telegramClient);

        verify(userService).resetToIdle(user);
        verifyNoInteractions(plantTemplateService, plantCardService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Контекст создания повреждён");
    }

    @Test
    @DisplayName("Сбрасывает в IDLE и сообщает ошибку, если шаблон не найден")
    void shouldResetToIdleWhenTemplateNotFound() throws TelegramApiException {
        Update update = textUpdate("Монстера");
        when(plantTemplateService.getTemplate(1L, 42L)).thenReturn(Optional.empty());

        handler.handle(user, update, telegramClient);

        verify(userService).resetToIdle(user);
        verify(plantCardService, never()).finishPlantCreation(any(), any(), any());

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Шаблон не найден");
    }

    @Test
    @DisplayName("Сбрасывает в IDLE, если создание из шаблона бросает IllegalArgumentException")
    void shouldResetToIdleWhenCreationFails() throws TelegramApiException {
        Update update = textUpdate("Монстера");
        PlantTemplate template = templateWithId(42L, "Монстера базовая");

        when(plantTemplateService.getTemplate(1L, 42L)).thenReturn(Optional.of(template));
        when(plantTemplateService.createPlantFromTemplate(user, 42L, "Монстера"))
                .thenThrow(new IllegalArgumentException("Лимит растений исчерпан"));

        handler.handle(user, update, telegramClient);

        verify(userService).resetToIdle(user);
        verify(plantCardService, never()).finishPlantCreation(any(), any(), any());

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Лимит растений исчерпан");
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

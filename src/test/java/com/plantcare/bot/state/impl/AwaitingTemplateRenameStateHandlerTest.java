package com.plantcare.bot.state.impl;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для AwaitingTemplateRenameStateHandler")
class AwaitingTemplateRenameStateHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private PlantTemplateService plantTemplateService;

    @Mock
    private TelegramClient telegramClient;

    @InjectMocks
    private AwaitingTemplateRenameStateHandler handler;

    private User user;

    @BeforeEach
    void setUp() {
        Map<String, Object> stateData = new HashMap<>();
        stateData.put("rename_template_id", "5");

        user = User.builder()
                .telegramChatId(300L)
                .stateData(stateData)
                .build();
        ReflectionTestUtils.setField(user, "id", 1L);
    }

    @Test
    @DisplayName("Поддерживает состояние AWAITING_TEMPLATE_RENAME")
    void shouldSupportExpectedState() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_TEMPLATE_RENAME);
    }

    @Test
    @DisplayName("Переименовывает шаблон и сбрасывает в IDLE при валидном имени")
    void shouldRenameTemplateAndResetToIdle() throws TelegramApiException {
        Update update = textUpdate("Новое имя");
        PlantTemplate updated = PlantTemplate.builder().name("Новое имя").build();
        ReflectionTestUtils.setField(updated, "id", 5L);

        when(plantTemplateService.renameTemplate(user, 5L, "Новое имя")).thenReturn(updated);

        handler.handle(user, update, telegramClient);

        verify(userService).resetToIdle(user);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Шаблон переименован");
        assertThat(captor.getValue().getText()).contains("Новое имя");
    }

    @Test
    @DisplayName("Просит ввести название, если апдейт без текста")
    void shouldPromptWhenUpdateHasNoText() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasMessage()).thenReturn(false);

        handler.handle(user, update, telegramClient);

        verifyNoInteractions(plantTemplateService, userService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Введи новое название шаблона");
    }

    @Test
    @DisplayName("Отклоняет пустое имя валидацией PlantTemplateService.validateName и не сбрасывает state")
    void shouldRejectBlankNameWithoutResettingState() throws TelegramApiException {
        Update update = textUpdate("   ");

        handler.handle(user, update, telegramClient);

        verify(userService, never()).resetToIdle(any());
        verifyNoInteractions(plantTemplateService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("не может быть пустым");
    }

    @Test
    @DisplayName("Отклоняет имя длиннее 40 символов")
    void shouldRejectNameLongerThan40Chars() throws TelegramApiException {
        Update update = textUpdate("а".repeat(41));

        handler.handle(user, update, telegramClient);

        verify(userService, never()).resetToIdle(any());
        verifyNoInteractions(plantTemplateService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("не должно превышать 40 символов");
    }

    @Test
    @DisplayName("Сбрасывает в IDLE, если rename_template_id отсутствует")
    void shouldResetToIdleWhenTemplateIdMissing() throws TelegramApiException {
        user.setStateData(new HashMap<>());
        Update update = textUpdate("Новое имя");

        handler.handle(user, update, telegramClient);

        verify(userService).resetToIdle(user);
        verifyNoInteractions(plantTemplateService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Контекст переименования утерян");
    }

    @Test
    @DisplayName("Сбрасывает в IDLE, если rename_template_id не парсится как число")
    void shouldResetToIdleWhenTemplateIdIsNotNumeric() throws TelegramApiException {
        user.getStateData().put("rename_template_id", "xyz");
        Update update = textUpdate("Новое имя");

        handler.handle(user, update, telegramClient);

        verify(userService).resetToIdle(user);
        verifyNoInteractions(plantTemplateService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Контекст повреждён");
    }

    @Test
    @DisplayName("Не сбрасывает state, если переименование бросает IllegalArgumentException (даёт ввести другое имя)")
    void shouldKeepStateWhenRenameFails() throws TelegramApiException {
        Update update = textUpdate("Дубликат");
        when(plantTemplateService.renameTemplate(user, 5L, "Дубликат"))
                .thenThrow(new IllegalArgumentException("Шаблон с таким именем уже есть"));

        handler.handle(user, update, telegramClient);

        verify(userService, never()).resetToIdle(any());

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Шаблон с таким именем уже есть");
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

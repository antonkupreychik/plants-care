package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.PlantCardService;
import com.plantcare.bot.service.PlantService;
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
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit-тесты для AwaitingPlantRenameStateHandler")
class AwaitingPlantRenameStateHandlerTest {

    @Mock private UserService userService;
    @Mock private PlantService plantService;
    @Mock private PlantCardService plantCardService;
    @Mock private TelegramClient client;

    @InjectMocks
    private AwaitingPlantRenameStateHandler handler;

    private User user;

    @BeforeEach
    void setUp() {
        Map<String, Object> stateData = new HashMap<>();
        stateData.put("edit_plant_id", "42");
        stateData.put("edit_message_id", "100");
        stateData.put("edit_back_target", "LIST");

        user = User.builder()
                .telegramChatId(123L)
                .stateData(stateData)
                .build();
        setPrivateField(user, "id", 7L);
    }

    @Test
    @DisplayName("Возвращает корректный supported state")
    void shouldReturnSupportedState() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_PLANT_RENAME);
    }

    @Test
    @DisplayName("Валидное имя — переименовывает, шлёт подтверждение в чат, сбрасывает state, рендерит настройки")
    void happyPath_renamesAndReturnsToSettings() throws TelegramApiException {
        Update update = textUpdate("New Plant Name");

        handler.handle(user, update, client);

        verify(plantService).renamePlant(7L, 42L, "New Plant Name");
        verify(userService).resetToIdle(user);
        // Новый messageId=null → свежее меню приходит новым сообщением вниз чата,
        // а не редактирует старое (его юзер не видит — оно ушло вверх при скролле).
        verify(plantCardService).showSettingsScreen(user, 42L, null, "LIST", client);

        // Подтверждение в чат — пользователь видит «✅ Имя обновлено: …» сразу после своего ввода.
        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(cap.capture());
        assertThat(cap.getValue().getText())
                .contains("✅").contains("New Plant Name");
    }

    @Test
    @DisplayName("Пустое имя — подсказка, plant не трогается")
    void rejectsBlankName() throws TelegramApiException {
        Update update = textUpdate("   ");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(cap.capture());
        assertThat(cap.getValue().getText()).contains("от 1 до 100");

        verify(plantService, never()).renamePlant(any(), any(), anyString());
        verify(userService, never()).resetToIdle(user);
        verify(plantCardService, never()).showSettingsScreen(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Не-текстовое сообщение — подсказка")
    void rejectsNonText() {
        Update update = new Update();
        update.setMessage(new Message());

        handler.handle(user, update, client);

        verify(plantService, never()).renamePlant(any(), any(), anyString());
    }

    @Test
    @DisplayName("Отсутствует edit_plant_id — reset to idle + ошибка пользователю")
    void missingContext_resetsAndWarns() throws TelegramApiException {
        user.getStateData().remove("edit_plant_id");
        Update update = textUpdate("Whatever");

        handler.handle(user, update, client);

        verify(userService).resetToIdle(user);
        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(cap.capture());
        assertThat(cap.getValue().getText()).contains("Контекст редактирования");
        verify(plantService, never()).renamePlant(any(), any(), anyString());
    }

    @Test
    @DisplayName("PlantService бросает IllegalArgumentException — handler отвечает текстом, не падает")
    void serviceError_isCaught() throws TelegramApiException {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("Растение не найдено"))
                .when(plantService).renamePlant(7L, 42L, "Boom");

        handler.handle(user, textUpdate("Boom"), client);

        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(cap.capture());
        assertThat(cap.getValue().getText()).contains("Растение не найдено");
        verify(userService, never()).resetToIdle(user);
    }

    private Update textUpdate(String text) {
        Update update = new Update();
        Message msg = new Message();
        msg.setText(text);
        update.setMessage(msg);
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
            if (field == null) throw new RuntimeException("Field not found: " + fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

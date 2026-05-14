package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.PlantTemplate;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.PlantTemplateService;
import com.plantcare.bot.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AwaitingTemplateNameStateHandler — unit tests")
class AwaitingTemplateNameStateHandlerTest {

    @Mock private UserService userService;
    @Mock private PlantTemplateService plantTemplateService;
    @Mock private TelegramClient client;

    @InjectMocks
    private AwaitingTemplateNameStateHandler handler;

    private User user;
    private static final Long PLANT_ID = 42L;

    @BeforeEach
    void setUp() {
        Map<String, Object> stateData = new HashMap<>();
        stateData.put("save_template_plant_id", String.valueOf(PLANT_ID));

        user = User.builder()
                .telegramChatId(123L)
                .stateData(stateData)
                .build();
    }

    @Test
    @DisplayName("Возвращает корректное supported state")
    void shouldReturnCorrectSupportedState() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_TEMPLATE_NAME);
    }

    @Test
    @DisplayName("Валидное имя — сохраняет шаблон, сбрасывает state")
    void happyPath_savesTemplateAndResetsState() throws Exception {
        PlantTemplate saved = mock(PlantTemplate.class);
        when(saved.getName()).thenReturn("Мой шаблон");
        when(plantTemplateService.saveFromPlant(user, PLANT_ID, "Мой шаблон")).thenReturn(saved);

        handler.handle(user, textUpdate("Мой шаблон"), client);

        verify(plantTemplateService).saveFromPlant(user, PLANT_ID, "Мой шаблон");
        verify(userService).resetToIdle(user);
        verify(client).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("Пустое имя — подсказка, шаблон не сохраняется")
    void rejectsBlankName() throws Exception {
        handler.handle(user, textUpdate("   "), client);

        verify(plantTemplateService, never()).saveFromPlant(any(), any(), anyString());
        verify(userService, never()).resetToIdle(any());
        verify(client).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("Имя > 40 символов — подсказка")
    void rejectsTooLongName() throws Exception {
        handler.handle(user, textUpdate("А".repeat(41)), client);
        verify(plantTemplateService, never()).saveFromPlant(any(), any(), anyString());
    }

    @Test
    @DisplayName("Сервис кидает на дубль — подсказка без сброса state")
    void serviceThrowsOnDuplicate_stateNotReset() throws Exception {
        when(plantTemplateService.saveFromPlant(any(), any(), anyString()))
                .thenThrow(new IllegalArgumentException("уже существует"));

        handler.handle(user, textUpdate("Дубль"), client);

        verify(userService, never()).resetToIdle(any());
        verify(client).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("Сервис кидает на лимит — подсказка без сброса state")
    void serviceThrowsOnLimit_stateNotReset() throws Exception {
        when(plantTemplateService.saveFromPlant(any(), any(), anyString()))
                .thenThrow(new IllegalStateException("лимит"));

        handler.handle(user, textUpdate("Ещё"), client);

        verify(userService, never()).resetToIdle(any());
    }

    @Test
    @DisplayName("Не-текстовое сообщение — подсказка")
    void nonTextMessage_hintSent() throws Exception {
        Update update = new Update();
        update.setMessage(new Message());

        handler.handle(user, update, client);
        verify(client).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("Отсутствует save_template_plant_id — resetToIdle и подсказка")
    void missingPlantId_resetsToIdle() throws Exception {
        user.getStateData().remove("save_template_plant_id");

        handler.handle(user, textUpdate("Валидное имя"), client);

        verify(userService).resetToIdle(user);
        verify(plantTemplateService, never()).saveFromPlant(any(), any(), anyString());
    }

    private Update textUpdate(String text) {
        Update update = new Update();
        Message message = new Message();
        message.setText(text);
        update.setMessage(message);
        return update;
    }
}

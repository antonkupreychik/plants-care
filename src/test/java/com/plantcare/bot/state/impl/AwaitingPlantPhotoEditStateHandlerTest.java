package com.plantcare.bot.state.impl;

import com.plantcare.bot.service.PlantCardService;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.service.PlantService;
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
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для AwaitingPlantPhotoEditStateHandler")
class AwaitingPlantPhotoEditStateHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private PlantService plantService;

    @Mock
    private PlantCardService plantCardService;

    @Mock
    private TelegramClient telegramClient;

    @InjectMocks
    private AwaitingPlantPhotoEditStateHandler handler;

    private User user;

    @BeforeEach
    void setUp() {
        Map<String, Object> stateData = new HashMap<>();
        stateData.put("edit_plant_id", "11");
        stateData.put("edit_back_target", "CARD");

        user = User.builder()
                .telegramChatId(400L)
                .stateData(stateData)
                .build();
        ReflectionTestUtils.setField(user, "id", 1L);
    }

    @Test
    @DisplayName("Поддерживает состояние AWAITING_PLANT_PHOTO_EDIT")
    void shouldSupportExpectedState() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_PLANT_PHOTO_EDIT);
    }

    @Test
    @DisplayName("Сохраняет самое большое по размеру фото и открывает экран настроек")
    void shouldSaveLargestPhotoAndShowSettingsScreen() throws TelegramApiException {
        Update update = photoUpdate(
                photo("small", 100),
                photo("large", 5000),
                photo("medium", 2000)
        );

        handler.handle(user, update, telegramClient);

        verify(plantService).updatePhotoFileId(1L, 11L, "large");
        verify(userService).resetToIdle(user);
        verify(plantCardService).showSettingsScreen(user, 11L, null, "CARD", telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Фото обновлено");
    }

    @Test
    @DisplayName("Просит прислать фото, если апдейт без фото")
    void shouldPromptWhenUpdateHasNoPhoto() throws TelegramApiException {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasPhoto()).thenReturn(false);

        handler.handle(user, update, telegramClient);

        verifyNoInteractions(plantService, plantCardService, userService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Пришли фото картинкой");
    }

    @Test
    @DisplayName("Отклоняет фото с пустым (blank) fileId")
    void shouldRejectBlankFileId() throws TelegramApiException {
        Update update = photoUpdate(photo("   ", 100));

        handler.handle(user, update, telegramClient);

        verifyNoInteractions(plantService, plantCardService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Не удалось получить файл");
    }

    @Test
    @DisplayName("Сбрасывает в IDLE и сообщает об утере контекста, если edit_plant_id отсутствует")
    void shouldResetToIdleWhenPlantIdMissing() throws TelegramApiException {
        user.setStateData(new HashMap<>());
        Update update = photoUpdate(photo("file1", 100));

        handler.handle(user, update, telegramClient);

        verify(userService).resetToIdle(user);
        verifyNoInteractions(plantService, plantCardService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Контекст редактирования утерян");
    }

    @Test
    @DisplayName("Если сохранение фото падает — шлёт ошибку, не сбрасывает state и не открывает экран настроек")
    void shouldSendErrorWhenUpdatePhotoFails() throws TelegramApiException {
        Update update = photoUpdate(photo("file1", 100));
        doThrow(new RuntimeException("db down"))
                .when(plantService).updatePhotoFileId(1L, 11L, "file1");

        handler.handle(user, update, telegramClient);

        verify(userService, never()).resetToIdle(any());
        verifyNoInteractions(plantCardService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Не удалось сохранить фото");
    }

    @Test
    @DisplayName("Используется дефолтный backTarget (BACK_TO_LIST), если edit_back_target не задан")
    void shouldUseDefaultBackTargetWhenNotSet() throws TelegramApiException {
        Map<String, Object> stateData = new HashMap<>();
        stateData.put("edit_plant_id", "11");
        user.setStateData(stateData);

        Update update = photoUpdate(photo("file1", 100));

        handler.handle(user, update, telegramClient);

        verify(plantCardService).showSettingsScreen(
                user, 11L, null, PlantCardService.BACK_TO_LIST, telegramClient);
    }

    private Update photoUpdate(PhotoSize... photos) {
        Update update = mock(Update.class);
        Message message = mock(Message.class);

        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasPhoto()).thenReturn(true);
        when(message.getPhoto()).thenReturn(List.of(photos));

        return update;
    }

    private PhotoSize photo(String fileId, int fileSize) {
        PhotoSize photo = mock(PhotoSize.class);
        lenient().when(photo.getFileId()).thenReturn(fileId);
        lenient().when(photo.getFileSize()).thenReturn(fileSize);
        return photo;
    }
}

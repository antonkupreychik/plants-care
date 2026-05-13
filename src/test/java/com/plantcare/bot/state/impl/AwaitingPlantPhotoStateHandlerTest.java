package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.repository.PlantRepository;
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
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit-тесты для AwaitingPlantPhotoStateHandler")
class AwaitingPlantPhotoStateHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private PlantService plantService;

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private PlantCardService plantCardService;

    @Mock
    private TelegramClient client;

    @InjectMocks
    private AwaitingPlantPhotoStateHandler handler;

    private User user;
    private Plant plant;

    private final Long userId = 7L;
    private final Long chatId = 123L;
    private final Long plantId = 42L;
    private final String plantName = "Монстера";

    @BeforeEach
    void setUp() {
        Map<String, Object> stateData = new HashMap<>();
        stateData.put("plant_id", plantId.toString());

        user = User.builder()
                .telegramChatId(chatId)
                .stateData(stateData)
                .build();

        plant = Plant.builder()
                .name(plantName)
                .build();

        setPrivateField(user, "id", userId);
        setPrivateField(plant, "id", plantId);
    }

    @Test
    @DisplayName("Возвращает корректное поддерживаемое состояние")
    void shouldReturnSupportedState() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_PLANT_PHOTO);
    }

    @Test
    @DisplayName("Фото — сохраняет file_id самого большого PhotoSize и завершает создание")
    void shouldSavePhotoFileIdAndFinishCreation() throws Exception {
        Update update = photoUpdate(
                photoSize("small_id", 1000),
                photoSize("medium_id", 5000),
                photoSize("biggest_id", 20000)
        );

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.of(plant));

        Plant updated = Plant.builder().name(plantName).photoFileId("biggest_id").build();
        setPrivateField(updated, "id", plantId);

        when(plantService.updatePhotoFileId(userId, plantId, "biggest_id"))
                .thenReturn(updated);

        handler.handle(user, update, client);

        verify(plantService).updatePhotoFileId(userId, plantId, "biggest_id");
        verify(plantCardService).finishPlantCreation(user, updated, client);
    }

    @Test
    @DisplayName("Фото с null file_size — выбирается хоть какой-то file_id, без NPE")
    void shouldHandlePhotoWithNullFileSizes() throws Exception {
        Update update = photoUpdate(
                photoSize("only_id", null)
        );

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.of(plant));

        when(plantService.updatePhotoFileId(eq(userId), eq(plantId), eq("only_id")))
                .thenReturn(plant);

        handler.handle(user, update, client);

        verify(plantService).updatePhotoFileId(userId, plantId, "only_id");
        verify(plantCardService).finishPlantCreation(user, plant, client);
    }

    @Test
    @DisplayName("PHOTO:SKIP — не сохраняет фото, но завершает создание растения")
    void shouldSkipPhotoAndFinishCreation() throws Exception {
        Update update = callbackUpdate("PHOTO:SKIP");

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.of(plant));

        handler.handle(user, update, client);

        verify(client).execute(any(AnswerCallbackQuery.class));
        verify(plantService, never()).updatePhotoFileId(any(), any(), anyString());
        verify(plantCardService).finishPlantCreation(user, plant, client);
    }

    @Test
    @DisplayName("Текстовое сообщение вместо фото — отправляет подсказку")
    void shouldSendHintForTextMessage() throws Exception {
        Update update = textUpdate("какой-то текст");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Пришли фото")
                .contains("Пропустить");

        verify(plantService, never()).updatePhotoFileId(any(), any(), anyString());
        verifyNoInteractions(plantCardService);
    }

    @Test
    @DisplayName("Неизвестный callback — игнорируется без сохранения фото")
    void shouldIgnoreUnknownCallback() throws Exception {
        Update update = callbackUpdate("PHOTO:WAT");

        handler.handle(user, update, client);

        verify(client).execute(any(AnswerCallbackQuery.class));
        verify(plantService, never()).updatePhotoFileId(any(), any(), anyString());
        verifyNoInteractions(plantCardService);
    }

    @Test
    @DisplayName("Если plant_id отсутствует — отправляет ошибку и resetToIdle")
    void shouldResetToIdleWhenPlantIdMissing() throws Exception {
        user.getStateData().remove("plant_id");
        Update update = callbackUpdate("PHOTO:SKIP");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText()).contains("Не удалось найти растение");
        verify(userService).resetToIdle(user);
        verifyNoInteractions(plantCardService);
    }

    @Test
    @DisplayName("Если plant_id некорректный — отправляет ошибку и resetToIdle")
    void shouldResetToIdleWhenPlantIdInvalid() throws Exception {
        user.getStateData().put("plant_id", "abc");
        Update update = callbackUpdate("PHOTO:SKIP");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText()).contains("Некорректные данные растения");
        verify(userService).resetToIdle(user);
        verifyNoInteractions(plantCardService);
    }

    @Test
    @DisplayName("Если растение не найдено — отправляет ошибку и resetToIdle")
    void shouldResetToIdleWhenPlantNotFound() throws Exception {
        Update update = callbackUpdate("PHOTO:SKIP");

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.empty());

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText()).contains("Растение не найдено");
        verify(userService).resetToIdle(user);
        verifyNoInteractions(plantCardService);
    }

    @Test
    @DisplayName("Ошибка PlantService при сохранении фото — пишет в чат, но не валит handler")
    void shouldHandlePlantServiceFailureOnPhotoSave() throws Exception {
        Update update = photoUpdate(photoSize("file_id", 1000));

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.of(plant));

        when(plantService.updatePhotoFileId(userId, plantId, "file_id"))
                .thenThrow(new IllegalStateException("DB is down"));

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText()).contains("Не удалось сохранить фото");
        verifyNoInteractions(plantCardService);
    }

    // ---- helpers ----

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
        callbackQuery.setId("callback-id");
        callbackQuery.setData(data);
        update.setCallbackQuery(callbackQuery);
        return update;
    }

    private Update photoUpdate(PhotoSize... sizes) {
        Update update = new Update();
        Message message = new Message();
        message.setPhoto(List.of(sizes));
        update.setMessage(message);
        return update;
    }

    private PhotoSize photoSize(String fileId, Integer fileSize) {
        PhotoSize photo = new PhotoSize();
        photo.setFileId(fileId);
        photo.setFileSize(fileSize);
        return photo;
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

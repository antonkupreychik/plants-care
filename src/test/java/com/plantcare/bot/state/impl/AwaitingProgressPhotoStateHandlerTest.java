package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.PlantProgressPhoto;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.domain.enums.PhotoProgressFrequency;
import com.plantcare.bot.service.PhotoProgressCardService;
import com.plantcare.bot.service.PhotoProgressService;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit-тесты AwaitingProgressPhotoStateHandler (issue #72)")
class AwaitingProgressPhotoStateHandlerTest {

    private static final Long USER_ID = 7L;
    private static final Long CHAT_ID = 12345L;
    private static final Long PLANT_ID = 42L;

    @Mock
    private UserService userService;

    @Mock
    private PhotoProgressService photoProgressService;

    @Mock
    private PhotoProgressCardService photoProgressCardService;

    @Mock
    private TelegramClient client;

    @InjectMocks
    private AwaitingProgressPhotoStateHandler handler;

    private User user;
    private Plant plant;

    @BeforeEach
    void setUp() {
        Map<String, Object> stateData = new HashMap<>();
        stateData.put(AwaitingProgressPhotoStateHandler.STATE_KEY_PLANT_ID, PLANT_ID.toString());

        user = User.builder()
                .telegramChatId(CHAT_ID)
                .stateData(stateData)
                .timezone("UTC")
                .build();
        ReflectionTestUtils.setField(user, "id", USER_ID);

        plant = Plant.builder()
                .user(user)
                .name("Монстера")
                .photoProgressFrequency(PhotoProgressFrequency.P2W)
                .nextPhotoDueAt(LocalDateTime.now().plusWeeks(2))
                .build();
        ReflectionTestUtils.setField(plant, "id", PLANT_ID);
    }

    @Test
    void should_return_supported_state() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_PROGRESS_PHOTO);
    }

    @Test
    void should_save_photo_when_photo_received() throws Exception {
        Update update = photoUpdate(
                photoSize("small_id", 1000),
                photoSize("big_id", 20000)
        );
        PlantProgressPhoto saved = new PlantProgressPhoto();
        saved.setPlant(plant);
        saved.setUser(user);
        saved.setTakenAt(LocalDateTime.now());
        when(photoProgressService.addPhoto(USER_ID, PLANT_ID, "big_id")).thenReturn(saved);

        handler.handle(user, update, client);

        verify(photoProgressService).addPhoto(USER_ID, PLANT_ID, "big_id");
        verify(userService).resetToIdle(user);
        verify(photoProgressCardService).showPhotoProgressScreen(eq(user), eq(PLANT_ID), any(), eq(client));
    }

    @Test
    void should_send_confirmation_with_next_due_date() throws Exception {
        Update update = photoUpdate(photoSize("file_id", 5000));
        PlantProgressPhoto saved = new PlantProgressPhoto();
        saved.setPlant(plant);
        saved.setUser(user);
        saved.setTakenAt(LocalDateTime.now());
        when(photoProgressService.addPhoto(eq(USER_ID), eq(PLANT_ID), anyString())).thenReturn(saved);

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Фото добавлено")
                .contains("Следующее напоминание");
    }

    @Test
    void should_send_short_confirmation_when_frequency_off() throws Exception {
        plant.setPhotoProgressFrequency(PhotoProgressFrequency.OFF);
        plant.setNextPhotoDueAt(null);
        PlantProgressPhoto saved = new PlantProgressPhoto();
        saved.setPlant(plant);
        saved.setUser(user);
        saved.setTakenAt(LocalDateTime.now());
        Update update = photoUpdate(photoSize("file_id", 5000));
        when(photoProgressService.addPhoto(eq(USER_ID), eq(PLANT_ID), anyString())).thenReturn(saved);

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Фото добавлено")
                .doesNotContain("Следующее напоминание");
    }

    @Test
    void should_show_hint_when_text_received() throws Exception {
        Update update = textUpdate("какой-то текст");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Пришли фото");
        verify(photoProgressService, never()).addPhoto(anyLong(), anyLong(), anyString());
        verify(userService, never()).resetToIdle(any());
    }

    @Test
    void should_reset_state_and_open_screen_on_cancel_callback() throws Exception {
        Update update = callbackUpdate("PHOTO_PROGRESS:CANCEL");

        handler.handle(user, update, client);

        verify(client).execute(any(AnswerCallbackQuery.class));
        verify(userService).resetToIdle(user);
        verify(photoProgressCardService).showPhotoProgressScreen(eq(user), eq(PLANT_ID), any(), eq(client));
        verify(photoProgressService, never()).addPhoto(anyLong(), anyLong(), anyString());
    }

    @Test
    void should_ignore_unknown_callback_without_resetting_state() throws Exception {
        Update update = callbackUpdate("SOMETHING:ELSE");

        handler.handle(user, update, client);

        verify(client).execute(any(AnswerCallbackQuery.class));
        verify(userService, never()).resetToIdle(any());
        verifyNoInteractions(photoProgressCardService);
    }

    @Test
    void should_send_error_when_anti_spam_violated() throws Exception {
        Update update = photoUpdate(photoSize("file_id", 5000));
        when(photoProgressService.addPhoto(eq(USER_ID), eq(PLANT_ID), anyString()))
                .thenThrow(new IllegalStateException("Уже есть фото за последние 24 часа"));

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client, org.mockito.Mockito.atLeastOnce()).execute(captor.capture());

        assertThat(captor.getAllValues())
                .extracting(SendMessage::getText)
                .anyMatch(t -> t.contains("24 часа"));
        verify(userService).resetToIdle(user);
        verify(photoProgressCardService).showPhotoProgressScreen(eq(user), eq(PLANT_ID), any(), eq(client));
    }

    @Test
    void should_send_error_when_plant_not_found() throws Exception {
        Update update = photoUpdate(photoSize("file_id", 5000));
        when(photoProgressService.addPhoto(eq(USER_ID), eq(PLANT_ID), anyString()))
                .thenThrow(new IllegalArgumentException("Plant not found"));

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText()).contains("Растение не найдено");
        verify(userService).resetToIdle(user);
        verifyNoInteractions(photoProgressCardService);
    }

    @Test
    void should_reset_to_idle_when_plant_id_missing_in_state_data() throws Exception {
        user.getStateData().clear();
        Update update = photoUpdate(photoSize("file_id", 5000));

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText()).contains("контекст");
        verify(userService).resetToIdle(user);
        verify(photoProgressService, never()).addPhoto(anyLong(), anyLong(), anyString());
    }

    @Test
    void should_show_hint_when_photo_message_has_no_photo_size() throws Exception {
        Update update = new Update();
        Message message = new Message();
        // Photo with empty list — hasPhoto() returns false, so handler falls through to hint.
        message.setPhoto(List.of());
        update.setMessage(message);

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Пришли фото");
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
        callbackQuery.setId("cb-id");
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
        PhotoSize p = new PhotoSize();
        p.setFileId(fileId);
        p.setFileSize(fileSize);
        return p;
    }
}

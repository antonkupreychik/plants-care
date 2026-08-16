package com.plantcare.bot.service;

import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.PlantProgressPhoto;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.PhotoProgressFrequency;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.service.PhotoProgressService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhotoProgressCardServiceTest {

    private static final Long USER_ID = 10L;
    private static final Long PLANT_ID = 7L;

    @Mock
    private PhotoProgressService photoProgressService;
    @Mock
    private PlantRepository plantRepository;
    @Mock
    private TelegramClient telegramClient;

    @InjectMocks
    private PhotoProgressCardService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().telegramChatId(555L).timezone("UTC").build();
        ReflectionTestUtils.setField(user, "id", USER_ID);
    }

    private Plant plant(PhotoProgressFrequency frequency, LocalDateTime nextDue) {
        Plant plant = Plant.builder()
                .name("Монстера")
                .photoProgressFrequency(frequency)
                .nextPhotoDueAt(nextDue)
                .build();
        ReflectionTestUtils.setField(plant, "id", PLANT_ID);
        return plant;
    }

    private PlantProgressPhoto photo(long id, LocalDateTime takenAt) {
        PlantProgressPhoto photo = new PlantProgressPhoto();
        ReflectionTestUtils.setField(photo, "id", id);
        photo.setTelegramFileId("file-" + id);
        photo.setTakenAt(takenAt);
        return photo;
    }

    private void stubPlantFound(Plant plant) {
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(USER_ID, PLANT_ID))
                .thenReturn(Optional.of(plant));
    }

    private void stubPlantMissing() {
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(USER_ID, PLANT_ID))
                .thenReturn(Optional.empty());
    }

    private List<SendMessage> sentMessages() throws Exception {
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient, org.mockito.Mockito.atLeastOnce()).execute(captor.capture());
        return captor.getAllValues();
    }

    private List<String> buttonTexts(InlineKeyboardMarkup markup) {
        return markup.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getText)
                .toList();
    }

    private List<String> buttonCallbacks(InlineKeyboardMarkup markup) {
        return markup.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
    }

    // ==================== showPhotoProgressScreen ====================

    @Test
    void should_sendNotFoundMessage_when_plantMissingOnMainScreen() throws Exception {
        stubPlantMissing();

        service.showPhotoProgressScreen(user, PLANT_ID, null, telegramClient);

        assertThat(sentMessages()).anyMatch(m -> m.getText().equals("❌ Растение не найдено."));
    }

    @Test
    void should_showNoPhotosYetAndNoNextPushLine_when_frequencyIsOff() throws Exception {
        Plant plant = plant(PhotoProgressFrequency.OFF, null);
        stubPlantFound(plant);
        when(photoProgressService.countHistory(USER_ID, PLANT_ID)).thenReturn(0L);
        when(photoProgressService.getHistory(USER_ID, PLANT_ID, 0)).thenReturn(List.of());

        service.showPhotoProgressScreen(user, PLANT_ID, null, telegramClient);

        SendMessage sent = sentMessages().get(0);
        assertThat(sent.getText()).contains("Последнее фото: ещё нет");
        assertThat(sent.getText()).contains("Всего фото: 0");
        assertThat(sent.getText()).doesNotContain("Следующий пуш");
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sent.getReplyMarkup();
        assertThat(buttonCallbacks(markup)).doesNotContain("PHOTO_PROGRESS:HISTORY:" + PLANT_ID + ":0");
        assertThat(buttonCallbacks(markup)).doesNotContain("PHOTO_PROGRESS:COMPARE:" + PLANT_ID);
    }

    @Test
    void should_showOffLabelAndSkipNextPushLine_when_frequencyIsNull() throws Exception {
        Plant plant = plant(null, null);
        stubPlantFound(plant);
        when(photoProgressService.countHistory(USER_ID, PLANT_ID)).thenReturn(0L);
        when(photoProgressService.getHistory(USER_ID, PLANT_ID, 0)).thenReturn(List.of());

        service.showPhotoProgressScreen(user, PLANT_ID, null, telegramClient);

        SendMessage sent = sentMessages().get(0);
        assertThat(sent.getText()).contains("Режим: *Выключено*");
        assertThat(sent.getText()).doesNotContain("Следующий пуш");
    }

    @Test
    void should_showLastPhotoDateInUserTimezoneAndNextPushAndHistoryButton_when_onePhotoAndFrequencyEnabled() throws Exception {
        // 2026-08-15T21:30 UTC -> в Asia/Almaty (+5) это уже 2026-08-16.
        LocalDateTime takenAtUtc = LocalDateTime.of(2026, 8, 15, 21, 30);
        LocalDateTime nextDueUtc = LocalDateTime.of(2026, 8, 29, 21, 30);
        user.setTimezone("Asia/Almaty");
        Plant plant = plant(PhotoProgressFrequency.P2W, nextDueUtc);
        stubPlantFound(plant);
        when(photoProgressService.countHistory(USER_ID, PLANT_ID)).thenReturn(1L);
        when(photoProgressService.getHistory(USER_ID, PLANT_ID, 0))
                .thenReturn(List.of(photo(1L, takenAtUtc)));

        service.showPhotoProgressScreen(user, PLANT_ID, null, telegramClient);

        SendMessage sent = sentMessages().get(0);
        assertThat(sent.getText()).contains("Последнее фото: 16.08.2026");
        assertThat(sent.getText()).contains("Следующий пуш: 30.08.2026");
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sent.getReplyMarkup();
        assertThat(buttonCallbacks(markup)).contains("PHOTO_PROGRESS:HISTORY:" + PLANT_ID + ":0");
        assertThat(buttonCallbacks(markup)).doesNotContain("PHOTO_PROGRESS:COMPARE:" + PLANT_ID);
    }

    @Test
    void should_skipNextPushLine_when_frequencyEnabledButNextDueAtIsNull() throws Exception {
        Plant plant = plant(PhotoProgressFrequency.P2W, null);
        stubPlantFound(plant);
        when(photoProgressService.countHistory(USER_ID, PLANT_ID)).thenReturn(0L);
        when(photoProgressService.getHistory(USER_ID, PLANT_ID, 0)).thenReturn(List.of());

        service.showPhotoProgressScreen(user, PLANT_ID, null, telegramClient);

        assertThat(sentMessages().get(0).getText()).doesNotContain("Следующий пуш");
    }

    @Test
    void should_renderEmptyPlantName_when_plantNameIsNullOnMainScreen() throws Exception {
        Plant plant = Plant.builder().photoProgressFrequency(PhotoProgressFrequency.OFF).build();
        ReflectionTestUtils.setField(plant, "id", PLANT_ID);
        stubPlantFound(plant);
        when(photoProgressService.countHistory(USER_ID, PLANT_ID)).thenReturn(0L);
        when(photoProgressService.getHistory(USER_ID, PLANT_ID, 0)).thenReturn(List.of());

        service.showPhotoProgressScreen(user, PLANT_ID, null, telegramClient);

        assertThat(sentMessages().get(0).getText()).contains("Фото-прогресс — *");
    }

    @Test
    void should_showCompareButton_when_totalIsAtLeastTwo() throws Exception {
        Plant plant = plant(PhotoProgressFrequency.OFF, null);
        stubPlantFound(plant);
        when(photoProgressService.countHistory(USER_ID, PLANT_ID)).thenReturn(2L);
        when(photoProgressService.getHistory(USER_ID, PLANT_ID, 0))
                .thenReturn(List.of(photo(1L, LocalDateTime.of(2026, 1, 1, 10, 0))));

        service.showPhotoProgressScreen(user, PLANT_ID, null, telegramClient);

        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sentMessages().get(0).getReplyMarkup();
        assertThat(buttonCallbacks(markup)).contains("PHOTO_PROGRESS:COMPARE:" + PLANT_ID);
    }

    @Test
    void should_editExistingMessage_when_messageIdProvided() throws Exception {
        Plant plant = plant(PhotoProgressFrequency.OFF, null);
        stubPlantFound(plant);
        when(photoProgressService.countHistory(USER_ID, PLANT_ID)).thenReturn(0L);
        when(photoProgressService.getHistory(USER_ID, PLANT_ID, 0)).thenReturn(List.of());

        service.showPhotoProgressScreen(user, PLANT_ID, 42, telegramClient);

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(captor.capture());
        EditMessageText edit = captor.getValue();
        assertThat(edit.getChatId()).isEqualTo("555");
        assertThat(edit.getMessageId()).isEqualTo(42);
        assertThat(edit.getText()).contains("Фото-прогресс");
        verify(telegramClient, never()).execute(any(SendMessage.class));
    }

    @Test
    void should_fallBackToSendMessage_when_editThrowsUnrelatedError() throws Exception {
        Plant plant = plant(PhotoProgressFrequency.OFF, null);
        stubPlantFound(plant);
        when(photoProgressService.countHistory(USER_ID, PLANT_ID)).thenReturn(0L);
        when(photoProgressService.getHistory(USER_ID, PLANT_ID, 0)).thenReturn(List.of());
        doThrow(new TelegramApiException("network error"))
                .when(telegramClient).execute(any(EditMessageText.class));

        service.showPhotoProgressScreen(user, PLANT_ID, 42, telegramClient);

        verify(telegramClient).execute(any(EditMessageText.class));
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @Test
    void should_notFallBackToSendMessage_when_editFailsBecauseMessageNotModified() throws Exception {
        Plant plant = plant(PhotoProgressFrequency.OFF, null);
        stubPlantFound(plant);
        when(photoProgressService.countHistory(USER_ID, PLANT_ID)).thenReturn(0L);
        when(photoProgressService.getHistory(USER_ID, PLANT_ID, 0)).thenReturn(List.of());
        doThrow(new TelegramApiException("Bad Request: message is not modified"))
                .when(telegramClient).execute(any(EditMessageText.class));

        service.showPhotoProgressScreen(user, PLANT_ID, 42, telegramClient);

        verify(telegramClient).execute(any(EditMessageText.class));
        verify(telegramClient, never()).execute(any(SendMessage.class));
    }

    @Test
    void should_treatNullExceptionMessageAsEmpty_when_editThrowsWithoutMessage() throws Exception {
        Plant plant = plant(PhotoProgressFrequency.OFF, null);
        stubPlantFound(plant);
        when(photoProgressService.countHistory(USER_ID, PLANT_ID)).thenReturn(0L);
        when(photoProgressService.getHistory(USER_ID, PLANT_ID, 0)).thenReturn(List.of());
        doThrow(new TelegramApiException()).when(telegramClient).execute(any(EditMessageText.class));

        service.showPhotoProgressScreen(user, PLANT_ID, 42, telegramClient);

        // Пустое сообщение об ошибке не совпадает с "message is not modified" -> идёт fallback.
        verify(telegramClient).execute(any(EditMessageText.class));
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @Test
    void should_swallowException_when_editFailsAndFallbackSendAlsoFails() throws Exception {
        Plant plant = plant(PhotoProgressFrequency.OFF, null);
        stubPlantFound(plant);
        when(photoProgressService.countHistory(USER_ID, PLANT_ID)).thenReturn(0L);
        when(photoProgressService.getHistory(USER_ID, PLANT_ID, 0)).thenReturn(List.of());
        doThrow(new TelegramApiException("network error")).when(telegramClient).execute(any(EditMessageText.class));
        doThrow(new TelegramApiException("still down")).when(telegramClient).execute(any(SendMessage.class));

        service.showPhotoProgressScreen(user, PLANT_ID, 42, telegramClient);

        verify(telegramClient).execute(any(EditMessageText.class));
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @Test
    void should_swallowException_when_plainSendTextFails() throws Exception {
        stubPlantMissing();
        doThrow(new TelegramApiException("down")).when(telegramClient).execute(any(SendMessage.class));

        service.showPhotoProgressScreen(user, PLANT_ID, null, telegramClient);

        verify(telegramClient).execute(any(SendMessage.class));
    }

    // ==================== showFrequencyChoice ====================

    @Test
    void should_sendNotFoundMessage_when_plantMissingOnFrequencyChoice() throws Exception {
        stubPlantMissing();

        service.showFrequencyChoice(user, PLANT_ID, null, telegramClient);

        assertThat(sentMessages()).anyMatch(m -> m.getText().equals("❌ Растение не найдено."));
    }

    @Test
    void should_markCurrentFrequencyAndListAllOptions_when_plantFound() throws Exception {
        Plant plant = plant(PhotoProgressFrequency.P2W, null);
        stubPlantFound(plant);

        service.showFrequencyChoice(user, PLANT_ID, null, telegramClient);

        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sentMessages().get(0).getReplyMarkup();
        assertThat(buttonTexts(markup)).contains("✅ Раз в 2 недели", "Раз в месяц", "Выключено");
        assertThat(buttonCallbacks(markup)).contains(
                "PHOTO_PROGRESS:FREQ:SET:" + PLANT_ID + ":P2W",
                "PHOTO_PROGRESS:FREQ:SET:" + PLANT_ID + ":P1M",
                "PHOTO_PROGRESS:FREQ:SET:" + PLANT_ID + ":OFF",
                "PHOTO_PROGRESS:VIEW:" + PLANT_ID
        );
    }

    // ==================== showHistory ====================

    @Test
    void should_sendNotFoundMessage_when_plantMissingOnHistory() throws Exception {
        stubPlantMissing();

        service.showHistory(user, PLANT_ID, 0, null, telegramClient);

        assertThat(sentMessages()).anyMatch(m -> m.getText().equals("❌ Растение не найдено."));
    }

    @Test
    void should_showEmptyHistoryMessageWithoutNavRow_when_noPhotos() throws Exception {
        Plant plant = plant(PhotoProgressFrequency.OFF, null);
        stubPlantFound(plant);
        when(photoProgressService.countHistory(USER_ID, PLANT_ID)).thenReturn(0L);
        when(photoProgressService.getHistory(USER_ID, PLANT_ID, 0)).thenReturn(List.of());

        service.showHistory(user, PLANT_ID, 0, null, telegramClient);

        SendMessage sent = sentMessages().get(0);
        assertThat(sent.getText()).contains("пусто");
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sent.getReplyMarkup();
        // Только кнопка "Назад" — без строки пагинации, т.к. totalPages == 1.
        assertThat(markup.getKeyboard()).hasSize(1);
    }

    @Test
    void should_omitPageIndicator_when_singlePageWithPhotos() throws Exception {
        Plant plant = plant(PhotoProgressFrequency.OFF, null);
        stubPlantFound(plant);
        when(photoProgressService.countHistory(USER_ID, PLANT_ID)).thenReturn(3L);
        when(photoProgressService.getHistory(USER_ID, PLANT_ID, 0)).thenReturn(List.of(
                photo(1L, LocalDateTime.of(2026, 1, 1, 0, 0))));

        service.showHistory(user, PLANT_ID, 0, null, telegramClient);

        assertThat(sentMessages().get(0).getText()).doesNotContain("Страница");
    }

    @Test
    void should_showBothNavigationDirections_when_onMiddlePage() throws Exception {
        Plant plant = plant(PhotoProgressFrequency.OFF, null);
        stubPlantFound(plant);
        when(photoProgressService.countHistory(USER_ID, PLANT_ID)).thenReturn(25L);
        List<PlantProgressPhoto> page = new ArrayList<>();
        for (long i = 0; i < 10; i++) {
            page.add(photo(100 + i, LocalDateTime.of(2026, 2, 1, 0, 0).plusDays(i)));
        }
        when(photoProgressService.getHistory(USER_ID, PLANT_ID, 1)).thenReturn(page);

        service.showHistory(user, PLANT_ID, 1, null, telegramClient);

        SendMessage sent = sentMessages().get(0);
        assertThat(sent.getText()).contains("Страница 2 из 3");
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sent.getReplyMarkup();
        assertThat(buttonTexts(markup)).contains("← Назад", "2/3", "Вперёд →");
        assertThat(buttonCallbacks(markup)).contains("PHOTO_PROGRESS:HISTORY:VIEW:100");
    }

    @Test
    void should_hideBackNav_when_onFirstPage() throws Exception {
        Plant plant = plant(PhotoProgressFrequency.OFF, null);
        stubPlantFound(plant);
        when(photoProgressService.countHistory(USER_ID, PLANT_ID)).thenReturn(25L);
        List<PlantProgressPhoto> page = new ArrayList<>();
        for (long i = 0; i < 10; i++) {
            page.add(photo(200 + i, LocalDateTime.of(2026, 2, 1, 0, 0).plusDays(i)));
        }
        when(photoProgressService.getHistory(USER_ID, PLANT_ID, 0)).thenReturn(page);

        service.showHistory(user, PLANT_ID, 0, null, telegramClient);

        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sentMessages().get(0).getReplyMarkup();
        assertThat(buttonTexts(markup)).doesNotContain("← Назад");
        assertThat(buttonTexts(markup)).contains("Вперёд →");
    }

    @Test
    void should_hideForwardNav_when_onLastPage() throws Exception {
        Plant plant = plant(PhotoProgressFrequency.OFF, null);
        stubPlantFound(plant);
        when(photoProgressService.countHistory(USER_ID, PLANT_ID)).thenReturn(25L);
        List<PlantProgressPhoto> page = List.of(photo(300, LocalDateTime.of(2026, 2, 1, 0, 0)));
        when(photoProgressService.getHistory(USER_ID, PLANT_ID, 2)).thenReturn(page);

        service.showHistory(user, PLANT_ID, 2, null, telegramClient);

        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sentMessages().get(0).getReplyMarkup();
        assertThat(buttonTexts(markup)).contains("← Назад");
        assertThat(buttonTexts(markup)).doesNotContain("Вперёд →");
    }

    @Test
    void should_clampToLastPage_when_requestedPageIsBeyondRange() throws Exception {
        Plant plant = plant(PhotoProgressFrequency.OFF, null);
        stubPlantFound(plant);
        when(photoProgressService.countHistory(USER_ID, PLANT_ID)).thenReturn(25L);
        when(photoProgressService.getHistory(USER_ID, PLANT_ID, 2)).thenReturn(List.of());

        service.showHistory(user, PLANT_ID, 99, null, telegramClient);

        verify(photoProgressService).getHistory(USER_ID, PLANT_ID, 2);
    }

    @Test
    void should_clampToFirstPage_when_requestedPageIsNegative() throws Exception {
        Plant plant = plant(PhotoProgressFrequency.OFF, null);
        stubPlantFound(plant);
        when(photoProgressService.countHistory(USER_ID, PLANT_ID)).thenReturn(25L);
        when(photoProgressService.getHistory(USER_ID, PLANT_ID, 0)).thenReturn(List.of());

        service.showHistory(user, PLANT_ID, -5, null, telegramClient);

        verify(photoProgressService).getHistory(USER_ID, PLANT_ID, 0);
    }

    // ==================== showPhoto ====================

    @Test
    void should_sendNotFoundMessage_when_photoMissing() throws Exception {
        when(photoProgressService.getPhotoForUser(USER_ID, 900L)).thenReturn(Optional.empty());

        service.showPhoto(user, 900L, telegramClient);

        assertThat(sentMessages()).anyMatch(m -> m.getText().equals("❌ Фото не найдено."));
    }

    @Test
    void should_sendPhotoWithFormattedCaption_when_photoFound() throws Exception {
        PlantProgressPhoto p = photo(1L, LocalDateTime.of(2026, 3, 10, 12, 0));
        when(photoProgressService.getPhotoForUser(USER_ID, 1L)).thenReturn(Optional.of(p));

        service.showPhoto(user, 1L, telegramClient);

        ArgumentCaptor<SendPhoto> captor = ArgumentCaptor.forClass(SendPhoto.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getCaption()).isEqualTo("🖼 10.03.2026");
        assertThat(captor.getValue().getChatId()).isEqualTo("555");
    }

    @Test
    void should_sendFallbackErrorMessage_when_sendPhotoThrows() throws Exception {
        PlantProgressPhoto p = photo(1L, LocalDateTime.of(2026, 3, 10, 12, 0));
        when(photoProgressService.getPhotoForUser(USER_ID, 1L)).thenReturn(Optional.of(p));
        doThrow(new TelegramApiException("boom")).when(telegramClient).execute(any(SendPhoto.class));

        service.showPhoto(user, 1L, telegramClient);

        assertThat(sentMessages()).anyMatch(m -> m.getText().equals("❌ Не удалось отправить фото."));
    }

    // ==================== showCompareMenu ====================

    @Test
    void should_sendNotFoundMessage_when_plantMissingOnCompareMenu() throws Exception {
        stubPlantMissing();

        service.showCompareMenu(user, PLANT_ID, null, telegramClient);

        assertThat(sentMessages()).anyMatch(m -> m.getText().equals("❌ Растение не найдено."));
    }

    @Test
    void should_sendMinimumTwoPhotosMessage_when_totalLessThanTwo() throws Exception {
        Plant plant = plant(PhotoProgressFrequency.OFF, null);
        stubPlantFound(plant);
        when(photoProgressService.countHistory(USER_ID, PLANT_ID)).thenReturn(1L);

        service.showCompareMenu(user, PLANT_ID, null, telegramClient);

        assertThat(sentMessages()).anyMatch(m -> m.getText().equals("Нужно минимум 2 фото для сравнения."));
    }

    @Test
    void should_showAllFourCompareOptions_when_totalIsAtLeastTwo() throws Exception {
        Plant plant = plant(PhotoProgressFrequency.OFF, null);
        stubPlantFound(plant);
        when(photoProgressService.countHistory(USER_ID, PLANT_ID)).thenReturn(2L);

        service.showCompareMenu(user, PLANT_ID, null, telegramClient);

        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sentMessages().get(0).getReplyMarkup();
        assertThat(buttonCallbacks(markup)).containsExactly(
                "PHOTO_PROGRESS:COMPARE:FIRST_LAST:" + PLANT_ID,
                "PHOTO_PROGRESS:COMPARE:MONTH_NOW:" + PLANT_ID,
                "PHOTO_PROGRESS:COMPARE:PICK:" + PLANT_ID,
                "PHOTO_PROGRESS:VIEW:" + PLANT_ID
        );
    }

    // ==================== showPickList ====================

    @Test
    void should_sendNotFoundMessage_when_plantMissingOnPickList() throws Exception {
        stubPlantMissing();

        service.showPickList(user, PLANT_ID, null, null, telegramClient);

        assertThat(sentMessages()).anyMatch(m -> m.getText().equals("❌ Растение не найдено."));
    }

    @Test
    void should_promptForFirstPhotoAndListLeftCallbacks_when_leftIdIsNull() throws Exception {
        Plant plant = plant(PhotoProgressFrequency.OFF, null);
        stubPlantFound(plant);
        when(photoProgressService.getRecent(USER_ID, PLANT_ID, 10))
                .thenReturn(List.of(
                        photo(11L, LocalDateTime.of(2026, 1, 1, 0, 0)),
                        photo(12L, LocalDateTime.of(2026, 1, 2, 0, 0))
                ));

        service.showPickList(user, PLANT_ID, null, null, telegramClient);

        SendMessage sent = sentMessages().get(0);
        assertThat(sent.getText()).contains("Шаг 1/2");
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sent.getReplyMarkup();
        assertThat(buttonCallbacks(markup)).contains(
                "PHOTO_PROGRESS:COMPARE:LEFT:" + PLANT_ID + ":11",
                "PHOTO_PROGRESS:COMPARE:LEFT:" + PLANT_ID + ":12"
        );
    }

    @Test
    void should_promptForSecondPhotoAndExcludeAlreadyChosenLeft_when_leftIdProvided() throws Exception {
        Plant plant = plant(PhotoProgressFrequency.OFF, null);
        stubPlantFound(plant);
        when(photoProgressService.getRecent(USER_ID, PLANT_ID, 10))
                .thenReturn(List.of(
                        photo(11L, LocalDateTime.of(2026, 1, 1, 0, 0)),
                        photo(12L, LocalDateTime.of(2026, 1, 2, 0, 0))
                ));

        service.showPickList(user, PLANT_ID, 11L, null, telegramClient);

        SendMessage sent = sentMessages().get(0);
        assertThat(sent.getText()).contains("Шаг 2/2");
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sent.getReplyMarkup();
        assertThat(buttonCallbacks(markup)).doesNotContain(
                "PHOTO_PROGRESS:COMPARE:DO:" + PLANT_ID + ":11:11");
        assertThat(buttonCallbacks(markup)).contains(
                "PHOTO_PROGRESS:COMPARE:DO:" + PLANT_ID + ":11:12");
    }

    // ==================== sendComparison ====================

    @Test
    void should_sendBeforeAfterPhotosAndDaysSummary_when_datesProvided() throws Exception {
        user.setTimezone("Asia/Almaty");
        PlantProgressPhoto before = photo(1L, LocalDateTime.of(2026, 1, 1, 10, 0));
        PlantProgressPhoto after = photo(2L, LocalDateTime.of(2026, 1, 15, 10, 0));
        PhotoProgressService.PhotoPair pair = new PhotoProgressService.PhotoPair(before, after);

        service.sendComparison(user, pair, telegramClient);

        ArgumentCaptor<SendPhoto> photoCaptor = ArgumentCaptor.forClass(SendPhoto.class);
        verify(telegramClient, times(2)).execute(photoCaptor.capture());
        assertThat(photoCaptor.getAllValues().get(0).getCaption()).isEqualTo("⏮ До — 01.01.2026");
        assertThat(photoCaptor.getAllValues().get(1).getCaption()).isEqualTo("⏭ После — 15.01.2026");

        assertThat(sentMessages()).anyMatch(m -> m.getText().equals("Прошло: 14 дн."));
    }

    @Test
    void should_sendFallbackErrorMessageAndStopComparison_when_firstPhotoSendFails() throws Exception {
        PlantProgressPhoto before = photo(1L, LocalDateTime.of(2026, 1, 1, 10, 0));
        PlantProgressPhoto after = photo(2L, LocalDateTime.of(2026, 1, 15, 10, 0));
        PhotoProgressService.PhotoPair pair = new PhotoProgressService.PhotoPair(before, after);
        doThrow(new TelegramApiException("boom")).when(telegramClient).execute(any(SendPhoto.class));

        service.sendComparison(user, pair, telegramClient);

        verify(telegramClient, times(1)).execute(any(SendPhoto.class));
        assertThat(sentMessages()).anyMatch(m -> m.getText().equals("❌ Не удалось отправить сравнение."));
        assertThat(sentMessages()).noneMatch(m -> m.getText().startsWith("Прошло:"));
    }

    // ==================== answerCallback ====================

    @Test
    void should_doNothing_when_callbackIdIsNull() throws Exception {
        service.answerCallback(telegramClient, null, "text");

        verify(telegramClient, never()).execute(any(AnswerCallbackQuery.class));
    }

    @Test
    void should_sendAnswerWithGivenText_when_callbackIdProvided() throws Exception {
        service.answerCallback(telegramClient, "cb-1", "Готово");

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getCallbackQueryId()).isEqualTo("cb-1");
        assertThat(captor.getValue().getText()).isEqualTo("Готово");
    }

    @Test
    void should_sendEmptyTextAnswer_when_textIsNull() throws Exception {
        service.answerCallback(telegramClient, "cb-1", null);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).isEqualTo("");
    }

    @Test
    void should_swallowException_when_answerCallbackExecuteThrows() throws Exception {
        doThrow(new TelegramApiException("boom")).when(telegramClient).execute(any(AnswerCallbackQuery.class));

        service.answerCallback(telegramClient, "cb-1", "text");

        verify(telegramClient).execute(any(AnswerCallbackQuery.class));
    }
}

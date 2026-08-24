package com.plantcare.bot.service;

import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.PlantArchiveService;
import jakarta.persistence.EntityNotFoundException;
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
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для PlantArchiveMenuService")
class PlantArchiveMenuServiceTest {

    @Mock private PlantArchiveService plantArchiveService;
    @Mock private PlantCardService plantCardService;
    @Mock private TelegramClient telegramClient;

    @InjectMocks
    private PlantArchiveMenuService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(555L)
                .timezone("UTC")
                .build();
    }

    private static Plant plantWithId(long id, String name) {
        Plant plant = Plant.builder().name(name).build();
        ReflectionTestUtils.setField(plant, "id", id);
        return plant;
    }

    // ==================== showArchiveList ====================

    @Test
    @DisplayName("showArchiveList c пустым архивом — текст «Архив пуст», кнопка назад, новое сообщение")
    void shouldShowEmptyArchiveMessage() throws TelegramApiException {
        when(plantArchiveService.listArchived(user.getId())).thenReturn(List.of());

        service.showArchiveList(user, null, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        SendMessage sent = captor.getValue();
        assertThat(sent.getText()).contains("Архив пуст.");
        assertThat(sent.getChatId()).isEqualTo("555");

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sent.getReplyMarkup();
        List<String> callbackData = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
        assertThat(callbackData).containsExactly("PLANT:LIST");
    }

    @Test
    @DisplayName("showArchiveList с растениями — строки списка и кнопки ARCHIVE:VIEW:<id>")
    void shouldListArchivedPlantsWithViewButtons() throws TelegramApiException {
        Plant p1 = plantWithId(11L, "Монстера");
        p1.setArchivedAt(LocalDateTime.of(2024, 3, 15, 10, 0));
        Plant p2 = plantWithId(12L, "Фикус");
        when(plantArchiveService.listArchived(user.getId())).thenReturn(List.of(p1, p2));

        service.showArchiveList(user, null, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        try {
            verify(telegramClient).execute(captor.capture());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
        String text = captor.getValue().getText();
        assertThat(text).contains("Монстера — в архиве с 15 марта 2024");
        // архивной даты нет у p2 — выводим прочерк
        assertThat(text).contains("Фикус — в архиве с —");

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
        List<String> callbackData = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
        assertThat(callbackData).containsExactly(
                "ARCHIVE:VIEW:11", "ARCHIVE:VIEW:12", "PLANT:LIST");
    }

    @Test
    @DisplayName("showArchiveList с messageId — редактирует сообщение, а не шлёт новое")
    void shouldEditExistingMessageWhenMessageIdPresent() throws TelegramApiException {
        when(plantArchiveService.listArchived(user.getId())).thenReturn(List.of());

        service.showArchiveList(user, 42, telegramClient);

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getMessageId()).isEqualTo(42);
        assertThat(captor.getValue().getChatId()).isEqualTo("555");
        verify(telegramClient, never()).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("showArchiveList — если редактирование падает с «message is not modified», фолбэк не шлётся")
    void shouldSwallowNotModifiedErrorOnEdit() throws TelegramApiException {
        when(plantArchiveService.listArchived(user.getId())).thenReturn(List.of());
        doThrow(new TelegramApiException("Bad Request: message is not modified"))
                .when(telegramClient).execute(any(EditMessageText.class));

        service.showArchiveList(user, 42, telegramClient);

        verify(telegramClient).execute(any(EditMessageText.class));
        verify(telegramClient, never()).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("showArchiveList — если редактирование падает с другой ошибкой, фолбэк новым сообщением")
    void shouldFallBackToNewMessageOnOtherEditError() throws TelegramApiException {
        when(plantArchiveService.listArchived(user.getId())).thenReturn(List.of());
        doThrow(new TelegramApiException("Bad Request: message to edit not found"))
                .when(telegramClient).execute(any(EditMessageText.class));

        service.showArchiveList(user, 42, telegramClient);

        verify(telegramClient).execute(any(EditMessageText.class));
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Архив пуст.");
    }

    // ==================== showArchiveCard ====================

    @Test
    @DisplayName("showArchiveCard — растение не найдено в архиве, алёрт-сообщение без клавиатуры")
    void shouldShowNotFoundMessageForMissingArchivedPlant() throws TelegramApiException {
        when(plantArchiveService.getArchivedOrThrow(user.getId(), 7L))
                .thenThrow(new EntityNotFoundException("not found"));

        service.showArchiveCard(user, 7L, null, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).isEqualTo("❌ Растение не найдено в архиве.");
        assertThat(captor.getValue().getReplyMarkup()).isNull();
    }

    @Test
    @DisplayName("showArchiveCard — карточка с поливами и месяцами, дата приобретения и лайв-локация")
    void shouldRenderCardWithWateringsAndMonths() throws TelegramApiException {
        Plant plant = plantWithId(9L, "Кактус");
        plant.setArchivedAt(LocalDateTime.of(2024, 6, 1, 12, 0));
        plant.setAcquiredAt(LocalDate.of(2023, 1, 1));
        Location location = Location.builder().name("Кухня").build();
        plant.setLocation(location);

        when(plantArchiveService.getArchivedOrThrow(user.getId(), 9L)).thenReturn(plant);
        when(plantArchiveService.countWaterings(9L)).thenReturn(20L);
        when(plantArchiveService.monthsLived(plant)).thenReturn(5L);

        service.showArchiveCard(user, 9L, null, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        try {
            verify(telegramClient).execute(captor.capture());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
        String text = captor.getValue().getText();
        assertThat(text).contains("Кактус");
        assertThat(text).contains("📦 В архиве с 01 июня 2024");
        assertThat(text).contains("20 поливов за 5 месяцев");

        verify(plantCardService).appendAcquiredAtLine(
                any(StringBuilder.class), eq(LocalDate.of(2023, 1, 1)), eq(LocalDate.of(2024, 6, 1)));

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
        List<String> callbackData = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
        assertThat(callbackData).containsExactly(
                "ARCHIVE:HISTORY:9", "ARCHIVE:RESTORE:9", "ARCHIVE:DELETE_CONFIRM:9", "ARCHIVE:LIST");
    }

    @Test
    @DisplayName("showArchiveCard — только поливы без месяцев, строка статистики без «за N месяцев»")
    void shouldRenderCardWithOnlyWateringsWhenMonthsZero() throws TelegramApiException {
        Plant plant = plantWithId(9L, "Кактус");
        plant.setArchivedAt(LocalDateTime.of(2024, 6, 1, 12, 0));

        when(plantArchiveService.getArchivedOrThrow(user.getId(), 9L)).thenReturn(plant);
        when(plantArchiveService.countWaterings(9L)).thenReturn(3L);
        when(plantArchiveService.monthsLived(plant)).thenReturn(0L);

        service.showArchiveCard(user, 9L, null, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        try {
            verify(telegramClient).execute(captor.capture());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
        String text = captor.getValue().getText();
        assertThat(text).contains("3 полива");
        assertThat(text).doesNotContain("за");
        verify(plantCardService, never()).appendAcquiredAtLine(any(), any(), any());
    }

    @Test
    @DisplayName("showArchiveCard — без поливов вообще, статистика не выводится")
    void shouldRenderCardWithoutStatsWhenNoWaterings() throws TelegramApiException {
        Plant plant = plantWithId(9L, "Кактус");
        plant.setArchivedAt(LocalDateTime.of(2024, 6, 1, 12, 0));

        when(plantArchiveService.getArchivedOrThrow(user.getId(), 9L)).thenReturn(plant);
        when(plantArchiveService.countWaterings(9L)).thenReturn(0L);
        when(plantArchiveService.monthsLived(plant)).thenReturn(0L);

        service.showArchiveCard(user, 9L, null, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        try {
            verify(telegramClient).execute(captor.capture());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
        assertThat(captor.getValue().getText()).doesNotContain("💧");
    }

    @Test
    @DisplayName("showArchiveCard — единственный полив и единственный месяц используют единственное число")
    void shouldUseSingularPluralFormsForOneWateringAndOneMonth() throws TelegramApiException {
        Plant plant = plantWithId(9L, "Кактус");
        plant.setArchivedAt(LocalDateTime.of(2024, 6, 1, 12, 0));

        when(plantArchiveService.getArchivedOrThrow(user.getId(), 9L)).thenReturn(plant);
        when(plantArchiveService.countWaterings(9L)).thenReturn(1L);
        when(plantArchiveService.monthsLived(plant)).thenReturn(1L);

        service.showArchiveCard(user, 9L, null, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        try {
            verify(telegramClient).execute(captor.capture());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
        assertThat(captor.getValue().getText()).contains("1 полив за 1 месяц");
    }

    @Test
    @DisplayName("showArchiveCard — 11 поливов за 11 месяцев используют множественное число (mod100=11 исключение)")
    void shouldUsePluralFormsForElevenWateringsAndElevenMonths() throws TelegramApiException {
        Plant plant = plantWithId(9L, "Кактус");
        plant.setArchivedAt(LocalDateTime.of(2024, 6, 1, 12, 0));

        when(plantArchiveService.getArchivedOrThrow(user.getId(), 9L)).thenReturn(plant);
        when(plantArchiveService.countWaterings(9L)).thenReturn(11L);
        when(plantArchiveService.monthsLived(plant)).thenReturn(11L);

        service.showArchiveCard(user, 9L, null, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        try {
            verify(telegramClient).execute(captor.capture());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
        assertThat(captor.getValue().getText()).contains("11 поливов за 11 месяцев");
    }

    @Test
    @DisplayName("showArchiveCard — 22 полива за 22 месяца используют форму «полива/месяца» (2-4 не-11..14)")
    void shouldUseFewFormsForTwentyTwoWateringsAndMonths() throws TelegramApiException {
        Plant plant = plantWithId(9L, "Кактус");
        plant.setArchivedAt(LocalDateTime.of(2024, 6, 1, 12, 0));

        when(plantArchiveService.getArchivedOrThrow(user.getId(), 9L)).thenReturn(plant);
        when(plantArchiveService.countWaterings(9L)).thenReturn(22L);
        when(plantArchiveService.monthsLived(plant)).thenReturn(22L);

        service.showArchiveCard(user, 9L, null, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        try {
            verify(telegramClient).execute(captor.capture());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
        assertThat(captor.getValue().getText()).contains("22 полива за 22 месяца");
    }

    @Test
    @DisplayName("showArchiveCard — имя со спецсимволами markdown экранируется")
    void shouldEscapeMarkdownSpecialCharsInPlantName() throws TelegramApiException {
        Plant plant = plantWithId(9L, "Fic*us_[1]`");
        plant.setArchivedAt(LocalDateTime.of(2024, 6, 1, 12, 0));

        when(plantArchiveService.getArchivedOrThrow(user.getId(), 9L)).thenReturn(plant);
        when(plantArchiveService.countWaterings(9L)).thenReturn(0L);
        when(plantArchiveService.monthsLived(plant)).thenReturn(0L);

        service.showArchiveCard(user, 9L, null, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        try {
            verify(telegramClient).execute(captor.capture());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
        assertThat(captor.getValue().getText()).contains("Fic\\*us\\_\\[1\\]\\`");
    }

    // ==================== showDeleteConfirm ====================

    @Test
    @DisplayName("showDeleteConfirm — растение не найдено, алёрт-сообщение")
    void shouldShowNotFoundOnDeleteConfirmForMissingPlant() throws TelegramApiException {
        when(plantArchiveService.getArchivedOrThrow(user.getId(), 3L))
                .thenThrow(new EntityNotFoundException("not found"));

        service.showDeleteConfirm(user, 3L, null, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).isEqualTo("❌ Растение не найдено в архиве.");
    }

    @Test
    @DisplayName("showDeleteConfirm — найдено, текст и клавиатура подтверждения удаления")
    void shouldShowDeleteConfirmationScreen() throws TelegramApiException {
        Plant plant = plantWithId(9L, "Кактус");
        when(plantArchiveService.getArchivedOrThrow(user.getId(), 9L)).thenReturn(plant);

        service.showDeleteConfirm(user, 9L, 100, telegramClient);

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        try {
            verify(telegramClient).execute(captor.capture());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
        String text = captor.getValue().getText();
        assertThat(text).contains("Удалить навсегда?");
        assertThat(text).contains("Кактус");

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
        List<String> callbackData = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
        assertThat(callbackData).containsExactly("ARCHIVE:DELETE_FINAL:9", "ARCHIVE:VIEW:9");
    }

    // ==================== sendArchivedPhotoIfPresent ====================

    @Test
    @DisplayName("sendArchivedPhotoIfPresent — без photoFileId фото не отправляется")
    void shouldNotSendPhotoWhenFileIdMissing() throws TelegramApiException {
        Plant plant = plantWithId(9L, "Кактус");

        service.sendArchivedPhotoIfPresent(user, plant, telegramClient);

        verify(telegramClient, never()).execute(any(SendPhoto.class));
    }

    @Test
    @DisplayName("sendArchivedPhotoIfPresent — пустая строка photoFileId тоже не отправляет фото")
    void shouldNotSendPhotoWhenFileIdBlank() throws TelegramApiException {
        Plant plant = plantWithId(9L, "Кактус");
        plant.setPhotoFileId("   ");

        service.sendArchivedPhotoIfPresent(user, plant, telegramClient);

        verify(telegramClient, never()).execute(any(SendPhoto.class));
    }

    @Test
    @DisplayName("sendArchivedPhotoIfPresent — с photoFileId отправляет SendPhoto с подписью")
    void shouldSendPhotoWithCaptionWhenFileIdPresent() throws TelegramApiException {
        Plant plant = plantWithId(9L, "Кактус");
        plant.setPhotoFileId("file-123");

        service.sendArchivedPhotoIfPresent(user, plant, telegramClient);

        ArgumentCaptor<SendPhoto> captor = ArgumentCaptor.forClass(SendPhoto.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getCaption()).isEqualTo("📦 Кактус");
        assertThat(captor.getValue().getChatId()).isEqualTo("555");
    }

    @Test
    @DisplayName("sendArchivedPhotoIfPresent — ошибка отправки не выбрасывается наружу")
    void shouldSwallowTelegramErrorOnPhotoSend() throws TelegramApiException {
        Plant plant = plantWithId(9L, "Кактус");
        plant.setPhotoFileId("file-123");
        doThrow(new TelegramApiException("boom")).when(telegramClient).execute(any(SendPhoto.class));

        service.sendArchivedPhotoIfPresent(user, plant, telegramClient);

        verify(telegramClient, times(1)).execute(any(SendPhoto.class));
    }
}

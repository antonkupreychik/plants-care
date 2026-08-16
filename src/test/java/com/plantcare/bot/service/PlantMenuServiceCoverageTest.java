package com.plantcare.bot.service;

import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.service.HealthScoreService;
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
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Дополняет PlantMenuServiceTest: остаточный gap — ветки catch/fallback в sendOrEdit
 * и case, когда у локации emoji задан, но пустая строка.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для PlantMenuService — residual coverage")
class PlantMenuServiceCoverageTest {

    @Mock private PlantRepository plantRepository;
    @Mock private HealthScoreService healthScoreService;
    @Mock private TelegramClient client;

    @InjectMocks
    private PlantMenuService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().telegramChatId(100L).build();
        ReflectionTestUtils.setField(user, "id", 7L);
        lenient().when(healthScoreService.computeForPlant(any()))
                .thenReturn(HealthScoreService.HealthScore.insufficient());
        lenient().when(plantRepository.findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(7L))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("Редактирование падает с «message is not modified» — фолбэк новым сообщением не шлётся")
    void shouldSwallowNotModifiedErrorWithoutFallback() throws TelegramApiException {
        doThrow(new TelegramApiException("Bad Request: message is not modified"))
                .when(client).execute(any(EditMessageText.class));

        service.sendMyPlantsList(user, 42, client);

        verify(client).execute(any(EditMessageText.class));
        verify(client, never()).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("Редактирование падает с другой ошибкой — фолбэк новым сообщением отправляется")
    void shouldFallBackToNewMessageOnOtherEditError() throws TelegramApiException {
        doThrow(new TelegramApiException("Bad Request: message to edit not found"))
                .when(client).execute(any(EditMessageText.class));

        service.sendMyPlantsList(user, 42, client);

        verify(client).execute(any(EditMessageText.class));
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getChatId()).isEqualTo("100");
        assertThat(captor.getValue().getText()).contains("Пока пусто");
    }

    @Test
    @DisplayName("Отправка нового сообщения падает — исключение не выбрасывается наружу")
    void shouldSwallowSendErrorWhenNoMessageId() throws TelegramApiException {
        doThrow(new TelegramApiException("boom")).when(client).execute(any(SendMessage.class));

        service.sendMyPlantsList(user, null, client);

        verify(client).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("У локации пустая строка emoji — используется фолбэк «· имя», а не пустой суффикс")
    void shouldFallBackToNameWhenLocationEmojiIsBlank() throws TelegramApiException {
        Location location = Location.builder().name("Балкон").emoji("").build();
        ReflectionTestUtils.setField(location, "id", 20L);
        Plant plant = Plant.builder().name("Пальма").location(location).build();
        ReflectionTestUtils.setField(plant, "id", 9L);

        when(plantRepository.findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(7L))
                .thenReturn(List.of(plant));

        service.sendMyPlantsList(user, null, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
        InlineKeyboardButton plantButton = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .filter(b -> "PLANT:VIEW:9".equals(b.getCallbackData()))
                .findFirst()
                .orElseThrow();
        assertThat(plantButton.getText()).isEqualTo("🌿 Пальма · Балкон");
    }
}

package com.plantcare.bot.service;

import com.plantcare.bot.domain.Species;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для MenuCallbackService")
class MenuCallbackServiceTest {

    @Mock private UserService userService;
    @Mock private PlantService plantService;
    @Mock private TelegramClient telegramClient;
    @Mock private CallbackQuery callbackQuery;
    @Mock private Message message;

    @InjectMocks
    private MenuCallbackService service;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .telegramChatId(100L)
                .timezone("UTC")
                .build();

        lenient().when(callbackQuery.getId()).thenReturn("cb-1");
        lenient().when(callbackQuery.getMessage()).thenReturn(message);
        lenient().when(message.getChatId()).thenReturn(100L);
    }

    @Test
    @DisplayName("MENU:ADD_PLANT переводит пользователя в AWAITING_PLANT_SPECIES_CHOICE")
    void shouldStartAddPlantFlow() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:ADD_PLANT");
        when(plantService.getPopularSpecies(6)).thenReturn(List.of());

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_SPECIES_CHOICE);
    }

    @Test
    @DisplayName("MENU:ADD_PLANT отправляет сообщение с клавиатурой видов")
    void shouldSendSpeciesKeyboardOnAddPlant() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:ADD_PLANT");
        List<Species> species = List.of(
                Species.builder().name("Монстера").popularity(100).build()
        );
        when(plantService.getPopularSpecies(6)).thenReturn(species);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        SendMessage sent = captor.getValue();
        assertThat(sent.getText()).contains("Давай добавим новое растение");
        assertThat(sent.getReplyMarkup()).isInstanceOf(InlineKeyboardMarkup.class);
    }

    @Test
    @DisplayName("MENU:ALL_PLANTS показывает заглушку")
    void shouldShowStubForAllPlants() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:ALL_PLANTS");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText()).contains("Скоро");
        verify(userService, never()).updateState(any(), any());
    }

    @Test
    @DisplayName("MENU:SETTINGS показывает заглушку")
    void shouldShowStubForSettings() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:SETTINGS");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText()).contains("Скоро");
    }

    @Test
    @DisplayName("Неизвестное действие — отправляет ошибку")
    void shouldHandleUnknownAction() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:UNKNOWN_ACTION");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText()).contains("❌");
    }
}
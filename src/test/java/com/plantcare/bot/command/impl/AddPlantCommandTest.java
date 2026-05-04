package com.plantcare.bot.command.impl;

import com.plantcare.bot.domain.Species;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
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
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для AddPlantCommand (/add)")
class AddPlantCommandTest {

    @Mock
    private TelegramClient telegramClient;

    @Mock
    private Update update;

    @Mock
    private Message message;

    @Mock
    private UserService userService;

    @Mock
    private PlantService plantService;

    @InjectMocks
    private AddPlantCommand addPlantCommand;

    private final Long chatId = 123L;
    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setTelegramChatId(chatId);

        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(chatId);
        when(userService.findByChatId(chatId)).thenReturn(Optional.of(testUser));
    }

    @Test
    @DisplayName("Должен переводить пользователя в состояние AWAITING_PLANT_SPECIES_CHOICE")
    void shouldTransitionToAwaitingSpeciesChoice() throws TelegramApiException {
        // GIVEN
        when(plantService.getPopularSpecies(6)).thenReturn(List.of());

        // WHEN
        addPlantCommand.execute(update, telegramClient);

        // THEN
        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_SPECIES_CHOICE);
    }

    @Test
    @DisplayName("Должен отправлять сообщение с инлайн-клавиатурой видов")
    void shouldSendMessageWithSpeciesKeyboard() throws TelegramApiException {
        // GIVEN
        List<Species> popular = List.of(
                Species.builder().name("Монстера").popularity(100).build(),
                Species.builder().name("Фикус").popularity(90).build()
        );
        when(plantService.getPopularSpecies(6)).thenReturn(popular);

        // WHEN
        addPlantCommand.execute(update, telegramClient);

        // THEN
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        SendMessage sent = captor.getValue();
        assertThat(sent.getChatId()).isEqualTo(chatId.toString());
        assertThat(sent.getText()).contains("Что за растение");
        assertThat(sent.getReplyMarkup()).isInstanceOf(InlineKeyboardMarkup.class);
    }

    @Test
    @DisplayName("Клавиатура содержит кнопки видов + Поиск + Своё")
    void shouldContainSpeciesAndServiceButtons() throws TelegramApiException {
        // GIVEN
        List<Species> popular = List.of(
                Species.builder().name("Монстера").popularity(100).build(),
                Species.builder().name("Фикус").popularity(90).build()
        );
        when(plantService.getPopularSpecies(6)).thenReturn(popular);

        // WHEN
        addPlantCommand.execute(update, telegramClient);

        // THEN
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
        List<String> allButtonTexts = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getText)
                .toList();

        assertThat(allButtonTexts).contains("Монстера", "Фикус");
        assertThat(allButtonTexts).anyMatch(text -> text.contains("Поиск"));
        assertThat(allButtonTexts).anyMatch(text -> text.contains("Своё"));
    }

    @Test
    @DisplayName("При пустом списке видов всё равно показываются Поиск и Своё")
    void shouldShowSearchAndCustomEvenWithNoSpecies() throws TelegramApiException {
        // GIVEN
        when(plantService.getPopularSpecies(6)).thenReturn(List.of());

        // WHEN
        addPlantCommand.execute(update, telegramClient);

        // THEN
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
        List<String> allButtonTexts = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getText)
                .toList();

        assertThat(allButtonTexts).anyMatch(text -> text.contains("Поиск"));
        assertThat(allButtonTexts).anyMatch(text -> text.contains("Своё"));
    }

    @Test
    @DisplayName("Должен обрабатывать TelegramApiException без выброса наружу")
    void shouldHandleTelegramApiException() throws TelegramApiException {
        // GIVEN
        when(plantService.getPopularSpecies(6)).thenReturn(List.of());
        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiException("API Error"));

        // WHEN & THEN — не должно выбросить исключение
        addPlantCommand.execute(update, telegramClient);

        verify(telegramClient).execute(any(SendMessage.class));
    }
}
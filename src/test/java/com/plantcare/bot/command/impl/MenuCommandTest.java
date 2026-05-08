package com.plantcare.bot.command.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.service.MainMenuService;
import com.plantcare.bot.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для MenuCommand (/menu)")
class MenuCommandTest {

    @Mock
    private TelegramClient telegramClient;

    @Mock
    private Update update;

    @Mock
    private Message message;

    @Mock
    private UserService userService;

    @Mock
    private MainMenuService mainMenuService;

    private MenuCommand menuCommand;

    private final Long chatId = 123L;
    private User testUser;

    @BeforeEach
    void setUp() {
        menuCommand = new MenuCommand(userService, mainMenuService);

        testUser = User.builder()
                .telegramChatId(chatId)
                .timezone("Europe/Riga")
                .build();
    }

    @Test
    @DisplayName("Команда зарегистрирована как /menu")
    void shouldHaveCorrectCommandName() {
        assertThat(menuCommand.getCommandName()).isEqualTo("/menu");
    }

    @Test
    @DisplayName("Должен найти пользователя и показать главное меню")
    void shouldFindUserAndSendMainMenu() {
        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(chatId);
        when(userService.findByChatId(chatId)).thenReturn(Optional.of(testUser));

        menuCommand.execute(update, telegramClient);

        verify(userService).findByChatId(chatId);
        verify(mainMenuService).sendMainMenu(testUser, telegramClient);
    }

    @Test
    @DisplayName("Если пользователь не найден — выбрасывает ошибку")
    void shouldThrowIfUserNotFound() {
        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(chatId);
        when(userService.findByChatId(chatId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> menuCommand.execute(update, telegramClient))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("User not found");

        verify(mainMenuService, never()).sendMainMenu(any(), any());
    }
}
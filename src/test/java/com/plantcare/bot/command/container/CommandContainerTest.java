package com.plantcare.bot.command.container;

import com.plantcare.bot.command.impl.UnknownCommand;
import com.plantcare.bot.command.interfaces.BotCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("Unit-тесты для CommandContainer")
class CommandContainerTest {

    private CommandContainer commandContainer;
    private BotCommand startCommand;
    private UnknownCommand unknownCommand;

    @BeforeEach
    void init() {
        // Создаем моки для команд
        startCommand = Mockito.mock(BotCommand.class);
        unknownCommand = Mockito.mock(UnknownCommand.class);

        // Определяем поведение мока для формирования Map
        Mockito.when(startCommand.getCommandName()).thenReturn("/start");

        // Инициализируем контейнер списком команд
        commandContainer = new CommandContainer(List.of(startCommand), unknownCommand);
    }

    @Test
    @DisplayName("Должен возвращать существующую команду по идентификатору")
    void shouldRetrieveExistingCommand() {
        // When
        BotCommand retrieved = commandContainer.retrieveCommand("/start");

        // Then
        assertEquals(startCommand, retrieved, "Должна вернуться команда /start");
        assertSame(startCommand, retrieved);
    }

    @Test
    @DisplayName("Должен возвращать UnknownCommand, если команда не найдена")
    void shouldRetrieveUnknownCommand() {
        // When
        BotCommand retrieved = commandContainer.retrieveCommand("/non-existent");

        // Then
        assertEquals(unknownCommand, retrieved, "Должна вернуться команда-заглушка");
        assertSame(unknownCommand, retrieved);
    }

    @Test
    @DisplayName("Должен возвращать UnknownCommand при передаче null или пустой строки")
    void shouldReturnUnknownCommandOnEmptyOrNull() {
        // Then
        assertEquals(unknownCommand, commandContainer.retrieveCommand(null));
        assertEquals(unknownCommand, commandContainer.retrieveCommand(""));
    }
}
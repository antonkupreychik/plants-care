package com.plantcare.bot.command.container;

import com.plantcare.bot.command.impl.UnknownCommand;
import com.plantcare.bot.command.interfaces.BotCommand;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CommandContainer {

    private final Map<String, BotCommand> commandMap;
    private final BotCommand unknownCommand;

    public CommandContainer(List<BotCommand> commands, UnknownCommand unknownCommand) {
        this.commandMap = commands.stream()
                .collect(Collectors.toMap(BotCommand::getCommandName, command -> command));
        this.unknownCommand = unknownCommand;
    }

    public BotCommand retrieveCommand(String commandIdentifier) {
        return commandMap.getOrDefault(commandIdentifier, unknownCommand);
    }
}
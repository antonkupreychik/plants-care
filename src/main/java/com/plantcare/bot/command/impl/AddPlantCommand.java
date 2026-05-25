package com.plantcare.bot.command.impl;

import com.plantcare.bot.command.interfaces.BotCommand;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.service.MessageService;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AddPlantCommand implements BotCommand {

    private final UserService userService;
    private final PlantService plantService;
    private final MessageService messageService;

    @Override
    public String getCommandName() {
        return "/add";
    }

    @Override
    public void execute(Update update, TelegramClient client) {
        Long chatId = update.getMessage().getChatId();
        User user = userService.findByChatId(chatId)
                .orElseThrow(() -> new IllegalStateException("User not found"));

        // Переходим в состояние ожидания выбора вида
        userService.updateState(user, ConversationState.AWAITING_PLANT_SPECIES_CHOICE);

        // Получаем популярные виды
        List<Species> popular = plantService.getPopularSpecies(6);

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(messageService.get(chatId, "command.add.prompt"))
                .replyMarkup(buildSpeciesKeyboard(chatId, popular))
                .build();

        try {
            client.execute(message);
            log.info("User {} initiated plant creation dialog", chatId);
        } catch (TelegramApiException e) {
            log.error("Failed to send species choice message", e);
        }
    }

    private InlineKeyboardMarkup buildSpeciesKeyboard(Long chatId, List<Species> species) {
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder<?, ?> builder = InlineKeyboardMarkup.builder();

        // Популярные виды (по 2 в ряду)
        for (int i = 0; i < species.size(); i += 2) {
            InlineKeyboardRow row = new InlineKeyboardRow();

            Species s1 = species.get(i);
            row.add(InlineKeyboardButton.builder()
                    .text(s1.getName())
                    .callbackData("SPECIES:" + s1.getId())
                    .build());

            if (i + 1 < species.size()) {
                Species s2 = species.get(i + 1);
                row.add(InlineKeyboardButton.builder()
                        .text(s2.getName())
                        .callbackData("SPECIES:" + s2.getId())
                        .build());
            }

            builder.keyboardRow(row);
        }

        // Дополнительные опции
        builder.keyboardRow(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text(messageService.get(chatId, "command.add.button.my_templates"))
                        .callbackData("SPECIES:MY_TEMPLATES")
                        .build()
        )));

        builder.keyboardRow(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text(messageService.get(chatId, "command.add.button.search"))
                        .callbackData("SPECIES:SEARCH")
                        .build()
        )));

        builder.keyboardRow(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text(messageService.get(chatId, "command.add.button.custom"))
                        .callbackData("SPECIES:CUSTOM")
                        .build()
        )));

        return builder.build();
    }
}

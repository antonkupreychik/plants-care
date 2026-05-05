package com.plantcare.bot.service;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Обрабатывает callback'и от inline-кнопок главного меню.
 * Формат callback_data: MENU:{action}
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MenuCallbackService {

    private final UserService userService;
    private final PlantService plantService;

    public void handleCallback(CallbackQuery callbackQuery, TelegramClient client, User user) {
        String data = callbackQuery.getData();
        String callbackId = callbackQuery.getId();
        Long chatId = callbackQuery.getMessage().getChatId();

        String action = data.substring("MENU:".length());

        switch (action) {
            case "ADD_PLANT" -> {
                // Переходим в состояние добавления растения (аналогично /add)
                userService.updateState(user, ConversationState.AWAITING_PLANT_SPECIES_CHOICE);

                var popular = plantService.getPopularSpecies(6);
                // Делегируем построение клавиатуры — отправляем сообщение как /add
                SendMessage message = SendMessage.builder()
                        .chatId(chatId.toString())
                        .text("🌿 Давай добавим новое растение!\n\nЧто за растение? Вот популярные виды:")
                        .replyMarkup(buildSpeciesKeyboard(popular))
                        .build();
                try {
                    client.execute(message);
                } catch (TelegramApiException e) {
                    log.error("Failed to start add plant from menu", e);
                }
                answerCallback(client, callbackId, "");
            }
            case "ALL_PLANTS" -> {
                answerCallback(client, callbackId, "🚧 Скоро будет доступно!");
            }
            case "SETTINGS" -> {
                answerCallback(client, callbackId, "🚧 Скоро будет доступно!");
            }
            default -> {
                answerCallback(client, callbackId, "❌ Неизвестная команда");
            }
        }
    }

    private void answerCallback(TelegramClient client, String callbackId, String text) {
        try {
            client.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to answer callback: {}", e.getMessage(), e);
        }
    }

    /**
     * Строит клавиатуру выбора вида — дублирует логику AddPlantCommand.
     * TODO: вынести в общий утилитный класс при рефакторинге.
     */
    private org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup buildSpeciesKeyboard(
            java.util.List<com.plantcare.bot.domain.Species> species) {
        var builder = org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup.builder();

        for (int i = 0; i < species.size(); i += 2) {
            var row = new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow();
            var s1 = species.get(i);
            row.add(org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                    .text(s1.getName())
                    .callbackData("SPECIES:" + s1.getId())
                    .build());
            if (i + 1 < species.size()) {
                var s2 = species.get(i + 1);
                row.add(org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                        .text(s2.getName())
                        .callbackData("SPECIES:" + s2.getId())
                        .build());
            }
            builder.keyboardRow(row);
        }

        builder.keyboardRow(new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow(
                java.util.List.of(
                        org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                                .text("🔍 Поиск")
                                .callbackData("SPECIES:SEARCH")
                                .build()
                )));

        builder.keyboardRow(new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow(
                java.util.List.of(
                        org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                                .text("✨ Своё без шаблона")
                                .callbackData("SPECIES:CUSTOM")
                                .build()
                )));

        return builder.build();
    }
}
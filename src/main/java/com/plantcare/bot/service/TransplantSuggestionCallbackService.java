package com.plantcare.bot.service;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.service.TransplantSuggestionService.ReactionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.springframework.stereotype.Service;

/**
 * Обработчик callback'ов подсказки расходников перед пересадкой (issue #141).
 *
 * <p>callback_data: {@code TRANSPLANT_SUGGEST:ADD:{id}} и
 * {@code TRANSPLANT_SUGGEST:SKIP:{id}}. Тонкий слой: парсит данные, дёргает
 * {@link TransplantSuggestionService}, отвечает {@code answerCallbackQuery} и
 * убирает кнопки у сообщения. Репозиториев не держит (CLAUDE.md).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransplantSuggestionCallbackService {

    public static final String CALLBACK_PREFIX = "TRANSPLANT_SUGGEST:";

    private final TransplantSuggestionService suggestionService;

    public void handleCallback(CallbackQuery callbackQuery, TelegramClient client, User user) {
        String data = callbackQuery.getData();
        String callbackId = callbackQuery.getId();
        Long chatId = callbackQuery.getMessage().getChatId();
        Integer messageId = callbackQuery.getMessage().getMessageId();

        String[] parts = data.split(":");
        if (parts.length != 3) {
            answerCallback(client, callbackId, "❌ Неизвестная команда");
            return;
        }

        String action = parts[1];

        long suggestionId;
        try {
            suggestionId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            answerCallback(client, callbackId, "❌ Неверный ID");
            return;
        }

        switch (action) {
            case "ADD" -> handleAdd(user, suggestionId, client, callbackId, chatId, messageId);
            case "SKIP" -> handleSkip(user, suggestionId, client, callbackId, chatId, messageId);
            default -> answerCallback(client, callbackId, "❌ Неизвестное действие");
        }
    }

    private void handleAdd(
            User user,
            long suggestionId,
            TelegramClient client,
            String callbackId,
            Long chatId,
            Integer messageId
    ) {
        ReactionResult result = suggestionService.addToShoppingList(user.getId(), suggestionId);

        switch (result) {
            case OK -> {
                editMessage(client, chatId, messageId,
                        "✅ Добавил в список покупок: " + suggestionService.suppliesLabel());
                answerCallback(client, callbackId, "Готово!");
            }
            case ALREADY_HANDLED -> {
                editMessage(client, chatId, messageId,
                        "✅ Уже добавлено в список покупок");
                answerCallback(client, callbackId, "Уже обработано");
            }
            case NOT_FOUND -> answerCallback(client, callbackId, "❌ Подсказка не найдена");
        }
    }

    private void handleSkip(
            User user,
            long suggestionId,
            TelegramClient client,
            String callbackId,
            Long chatId,
            Integer messageId
    ) {
        ReactionResult result = suggestionService.dismiss(user.getId(), suggestionId);

        switch (result) {
            case OK -> {
                editMessage(client, chatId, messageId, "Хорошо, не буду напоминать 👌");
                answerCallback(client, callbackId, "Готово");
            }
            case ALREADY_HANDLED -> {
                editMessage(client, chatId, messageId, "Хорошо, не буду напоминать 👌");
                answerCallback(client, callbackId, "Уже обработано");
            }
            case NOT_FOUND -> answerCallback(client, callbackId, "❌ Подсказка не найдена");
        }
    }

    private void editMessage(TelegramClient client, Long chatId, Integer messageId, String text) {
        EditMessageText edit = EditMessageText.builder()
                .chatId(chatId.toString())
                .messageId(messageId)
                .text(text)
                .replyMarkup(null)
                .build();

        try {
            client.execute(edit);
        } catch (TelegramApiException e) {
            log.error("Failed to edit transplant suggestion message: {}", e.getMessage(), e);
        }
    }

    private void answerCallback(TelegramClient client, String callbackId, String text) {
        try {
            client.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to answer transplant suggestion callback: {}", e.getMessage(), e);
        }
    }
}

package com.plantcare.bot.state.impl;

import com.plantcare.bot.service.SpeciesPreviewText;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
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
public class AwaitingPlantSpeciesSearchStateHandler implements StateHandler {

    private static final String BACK_TO_SPECIES = "BACK_TO_SPECIES";
    private static final String SEARCH_SELECT = "SEARCH_SELECT:";
    private static final String CONFIRM_TEMPLATE = "CONFIRM_TEMPLATE";

    private final UserService userService;
    private final PlantService plantService;

    private static final int MAX_SEARCH_RESULTS = 10;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_PLANT_SPECIES_SEARCH;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        Long chatId = user.getTelegramChatId();

        if (update.hasCallbackQuery()
                && update.getCallbackQuery().getMessage() != null
                && update.getCallbackQuery().getFrom().getId().equals(user.getTelegramChatId())) {
            String callbackData = update.getCallbackQuery().getData();

            if (callbackData.startsWith(SEARCH_SELECT)) {
                Long speciesId = Long.parseLong(callbackData.substring(SEARCH_SELECT.length()));
                selectFromSearch(user, speciesId, client);
            } else if (BACK_TO_SPECIES.equals(callbackData)) {
                // Вернуться к выбору вида (популярные)
                log.info("User {} returned to species choice from search", chatId);
                userService.updateState(user, ConversationState.AWAITING_PLANT_SPECIES_CHOICE);

                SendMessage message = SendMessage.builder()
                        .chatId(chatId.toString())
                        .text("🌿 Выбери вид растения или продолжи поиск:")
                        .build();

                try {
                    client.execute(message);
                } catch (TelegramApiException e) {
                    log.error("Failed to send back message", e);
                }
            } else if (CONFIRM_TEMPLATE.equals(callbackData)) {
                // Согласиться с выбранным видом - перейти к имени растения
                log.info("User {} confirmed template from search results", chatId);
                userService.updateState(user, ConversationState.AWAITING_PLANT_NAME);

                SendMessage message = SendMessage.builder()
                        .chatId(chatId.toString())
                        .text("📝 Как назовём твоё растение?\n\n" +
                                "Например: Монстера в гостиной, Фикус на окне")
                        .build();

                try {
                    client.execute(message);
                } catch (TelegramApiException e) {
                    log.error("Failed to send plant name request", e);
                }
            } else if ("EDIT_INTERVAL".equals(callbackData)) {
                // Изменить интервал полива
                log.info("User {} chose to edit interval from search results", chatId);
                userService.updateState(user, ConversationState.AWAITING_PLANT_WATERING_INTERVAL);

                SendMessage message = SendMessage.builder()
                        .chatId(chatId.toString())
                        .text("📅 Введи интервал полива в днях (от 1 до 365).\n\n" +
                                "Например: 7 — раз в неделю")
                        .build();

                try {
                    client.execute(message);
                } catch (TelegramApiException e) {
                    log.error("Failed to send interval request", e);
                }
            }

            answerCallback(update.getCallbackQuery().getId(), client);
            return;
        }

        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String query = update.getMessage().getText().trim();

        if (query.isEmpty()) {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text("Введи название растения, пожалуйста.")
                    .build();

            try {
                client.execute(message);
            } catch (TelegramApiException e) {
                log.error("Failed to send message", e);
            }
            return;
        }

        // Выполняем поиск
        List<Species> results = plantService.searchSpecies(query, MAX_SEARCH_RESULTS);

        if (results.isEmpty()) {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text("🔍 Ничего не найдено по запросу: \"" + query + "\"\n\n" +
                            "Попробуй другой запрос или выбери из популярных.")
                    .replyMarkup(buildBackKeyboard())
                    .build();

            try {
                client.execute(message);
            } catch (TelegramApiException e) {
                log.error("Failed to send no-results message", e);
            }
            return;
        }

        log.info("User {} searched for '{}', found {} results", chatId, query, results.size());

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text("🌿 Найдено растений: " + results.size() + "\n\nВыбери нужное:")
                .replyMarkup(buildSearchResultsKeyboard(results))
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send search results", e);
        }
    }

    private void selectFromSearch(User user, Long speciesId, TelegramClient client) {
        log.info("User {} selected species {} from search", user.getTelegramChatId(), speciesId);

        userService.setStateData(user, "species_id", speciesId.toString());

        // Сохраняем интервал полива из шаблона (по умолчанию 7 дней)
        // то же самое что в selectSpecies из AwaitingPlantSpeciesChoiceStateHandler

        plantService.getSpeciesById(speciesId).ifPresentOrElse(
                species -> {
                    int interval = species.getWateringDays() != null ? species.getWateringDays() : 7;
                    userService.setStateData(user, "interval_days", Integer.toString(interval));

                    SendMessage message = SendMessage.builder()
                            .chatId(user.getTelegramChatId().toString())
                            .text(SpeciesPreviewText.build(species.getName(), species.getWateringDays(), species.getLightPreference()))
                            .parseMode("Markdown")
                            .replyMarkup(buildConfirmKeyboard())
                            .build();

                    try {
                        client.execute(message);
                    } catch (TelegramApiException e) {
                        log.error("Failed to send confirmation message", e);
                    }
                },
                () -> {
                    SendMessage message = SendMessage.builder()
                            .chatId(user.getTelegramChatId().toString())
                            .text("❌ Вид не найден. Попробуй ещё раз.")
                            .build();

                    try {
                        client.execute(message);
                    } catch (TelegramApiException e) {
                        log.error("Failed to send error message", e);
                    }
                }
        );
    }

    private InlineKeyboardMarkup buildSearchResultsKeyboard(List<Species> species) {
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder<?, ?> builder = InlineKeyboardMarkup.builder();

        species
                .stream()
                .map(s -> new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text(s.getName())
                                .callbackData("SEARCH_SELECT:" + s.getId())
                                .build()
                )))
                .forEach(builder::keyboardRow);

        builder.keyboardRow(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ Назад")
                        .callbackData("BACK_TO_SPECIES")
                        .build()
        )));

        return builder.build();
    }

    private InlineKeyboardMarkup buildConfirmKeyboard() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("✅ Так и сделать")
                                .callbackData("CONFIRM_TEMPLATE")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("✏️ Изменить интервал")
                                .callbackData("EDIT_INTERVAL")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("⬅️ Выбрать другое")
                                .callbackData("BACK_TO_SPECIES")
                                .build()
                )))
                .build();
    }

    private InlineKeyboardMarkup buildBackKeyboard() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("⬅️ К популярным")
                                .callbackData("BACK_TO_SPECIES")
                                .build()
                )))
                .build();
    }

    private void answerCallback(String callbackQueryId, TelegramClient client) {
        try {
            client.execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQueryId)
                    .build());
        } catch (Exception e) {
            log.error("Failed to answer callback", e);
        }
    }
}
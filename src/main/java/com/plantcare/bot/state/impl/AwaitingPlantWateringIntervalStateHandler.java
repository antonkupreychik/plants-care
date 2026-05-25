package com.plantcare.bot.state.impl;

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
public class AwaitingPlantWateringIntervalStateHandler implements StateHandler {

    private final UserService userService;
    private final PlantService plantService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_PLANT_WATERING_INTERVAL;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        Long chatId = user.getTelegramChatId();

        if (update.hasCallbackQuery()
                && update.getCallbackQuery().getMessage() != null
                && update.getCallbackQuery().getFrom().getId().equals(user.getTelegramChatId())) {
            String callbackData = update.getCallbackQuery().getData();

            if ("CONFIRM_TEMPLATE".equals(callbackData)) {
                // Согласились с шаблонным интервалом
                proceedToName(user, client);
            } else if ("EDIT_INTERVAL".equals(callbackData)) {
                // Хотят изменить интервал
                askForCustomInterval(user, client);
            } else if ("BACK_TO_SPECIES".equals(callbackData)) {
                // Вернуться назад
                userService.updateState(user, ConversationState.AWAITING_PLANT_SPECIES_CHOICE);
                showSpeciesChoice(user, client);
            }

            try {
                client.execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                        .callbackQueryId(update.getCallbackQuery().getId())
                        .build());
            } catch (Exception e) {
                log.error("Failed to answer callback", e);
            }
            return;
        }

        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String text = update.getMessage().getText().trim();

        // Парсим введённый интервал
        try {
            int interval = Integer.parseInt(text);

            if (!PlantService.isValidInterval(interval)) {
                SendMessage message = SendMessage.builder()
                        .chatId(chatId.toString())
                        .text("❌ Интервал должен быть от 1 до 365 дней.\n\nПопробуй ещё раз:")
                        .build();

                try {
                    client.execute(message);
                } catch (TelegramApiException e) {
                    log.error("Failed to send validation error", e);
                }
                return;
            }

            log.info("User {} set watering interval to {} days", chatId, interval);
            userService.setStateData(user, "interval_days", String.valueOf(interval));
            proceedToName(user, client);

        } catch (NumberFormatException e) {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text("❌ Введи число от 1 до 365, пожалуйста.")
                    .build();

            try {
                client.execute(message);
            } catch (TelegramApiException ex) {
                log.error("Failed to send parse error", ex);
            }
        }
    }

    private void proceedToName(User user, TelegramClient client) {
        userService.updateState(user, ConversationState.AWAITING_PLANT_NAME);

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("📝 Как назовём твоё растение?\n\n" +
                        "Например: Монстера в гостиной, Фикус на окне")
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send plant name request", e);
        }
    }

    private void askForCustomInterval(User user, TelegramClient client) {
        userService.updateState(user, ConversationState.AWAITING_PLANT_WATERING_INTERVAL);

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("📅 Введи интервал полива в днях (от 1 до 365).\n\n" +
                        "Например: 7 — раз в неделю")
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send interval request", e);
        }
    }

    private void showSpeciesChoice(User user, TelegramClient client) {
        List<Species> popularSpecies = plantService.getPopularSpecies(6).stream()
                .toList();

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("🌿 Что за растение? Вот популярные виды:")
                .replyMarkup(buildSpeciesKeyboard(
                        popularSpecies
                ))
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send species choice", e);
        }
    }

    private InlineKeyboardMarkup buildSpeciesKeyboard(List<Species> species) {
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder<?, ?> builder = InlineKeyboardMarkup.builder();

        // Добавляем каждый вид в отдельном ряду
        for (Species s : species) {
            builder.keyboardRow(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text(s.getName())
                            .callbackData("SPECIES:" + s.getId())
                            .build()
            )));
        }

        // Кнопка поиска
        builder.keyboardRow(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("🔍 Поиск")
                        .callbackData("SPECIES:SEARCH")
                        .build()
        )));

        // Кнопка "Своё без шаблона"
        builder.keyboardRow(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("✨ Своё без шаблона")
                        .callbackData("SPECIES:CUSTOM")
                        .build()
        )));

        return builder.build();
    }
}
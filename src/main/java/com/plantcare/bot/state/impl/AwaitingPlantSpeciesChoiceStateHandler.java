package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.PlantTemplate;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.PlantService;
import com.plantcare.bot.service.PlantTemplateService;
import com.plantcare.bot.service.UserService;
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

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingPlantSpeciesChoiceStateHandler implements StateHandler {

    private final UserService userService;
    private final PlantService plantService;
    private final PlantTemplateService plantTemplateService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_PLANT_SPECIES_CHOICE;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        if (update.hasCallbackQuery()
                && update.getCallbackQuery().getMessage() != null
                && update.getCallbackQuery().getFrom().getId().equals(user.getTelegramChatId())) {
            String callbackData = update.getCallbackQuery().getData();

            if ("SPECIES:CUSTOM".equals(callbackData)) {        // ← проверяем специальные случаи ПЕРВЫМИ
                selectCustom(user, client);
            } else if ("SPECIES:SEARCH".equals(callbackData)) {
                goToSearch(user, client);
            } else if ("SPECIES:MY_TEMPLATES".equals(callbackData)) {
                showUserTemplates(user, client);
            } else if (callbackData.startsWith("TPL_PICK:")) {
                try {
                    Long templateId = Long.parseLong(callbackData.substring("TPL_PICK:".length()));
                    pickTemplate(user, templateId, client);
                } catch (NumberFormatException e) {
                    log.warn("Invalid template ID in callback: {}", callbackData);
                    sendError(user, client);
                }
            } else if (callbackData.startsWith("SPECIES:")) {   // ← потом проверяем общий случай
                try {
                    Long speciesId = Long.parseLong(callbackData.substring("SPECIES:".length()));
                    selectSpecies(user, speciesId, client);
                } catch (NumberFormatException e) {
                    log.warn("Invalid species ID in callback: {}", callbackData);
                    sendError(user, client);
                }
            }

            try {
                client.execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                        .callbackQueryId(update.getCallbackQuery().getId())
                        .build());
            } catch (Exception e) {
                log.error("Failed to answer callback", e);
            }
        }
    }

    private void selectSpecies(User user, Long speciesId, TelegramClient client) {
        log.info("User {} selected species {}", user.getTelegramChatId(), speciesId);

        userService.setStateData(user, "species_id", speciesId.toString());

        plantService.getSpeciesById(speciesId).ifPresentOrElse(
                species -> {
                    Integer interval = species.getWateringDays() != null ? species.getWateringDays() : 7;
                    userService.setStateData(user, "interval_days", interval.toString());
                    showPreview(user, species.getName(), species.getWateringDays(), client);
                },
                () -> sendError(user, client)
        );
    }

    private void selectCustom(User user, TelegramClient client) {
        log.info("User {} selected custom plant (no template)", user.getTelegramChatId());

        userService.setStateData(user, "species_id", "null");  // Маркер что это "своё"
        userService.updateState(user, ConversationState.AWAITING_PLANT_WATERING_INTERVAL);

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("Окей! 🌱 Вводи интервал полива в днях (от 1 до 365).\n\n" +
                        "Например: 7 — если поливаешь раз в неделю")
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send message", e);
        }
    }

    private void goToSearch(User user, TelegramClient client) {
        log.info("User {} initiated species search", user.getTelegramChatId());

        userService.updateState(user, ConversationState.AWAITING_PLANT_SPECIES_SEARCH);

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("🔍 Напиши название растения, которое ищешь.\n\n" +
                        "Например: монстера, фикус, орхидея")
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send message", e);
        }
    }

    private void showPreview(User user, String speciesName, Integer wateringDays, TelegramClient client) {
        String wateringText = wateringDays != null
                ? String.format("Полив: раз в %d дней", wateringDays)
                : "Полив: по необходимости";

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(String.format(
                        "✨ *%s*\n\n" +
                                "%s\n\n" +
                                "Тебе подходит?",
                        speciesName, wateringText
                ))
                .parseMode("Markdown")
                .replyMarkup(buildPreviewKeyboard(wateringDays))
                .build();
        userService.updateState(user, ConversationState.AWAITING_PLANT_WATERING_INTERVAL);
        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send preview message", e);
        }
    }

    private InlineKeyboardMarkup buildPreviewKeyboard(Integer wateringDays) {
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

    // ===== Пользовательские шаблоны (issue #68) =====

    private void showUserTemplates(User user, TelegramClient client) {
        List<PlantTemplate> templates = plantTemplateService.getUserTemplates(user.getId());
        Long chatId = user.getTelegramChatId();

        if (templates.isEmpty()) {
            SendMessage emptyMessage = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text("⭐ *Мои шаблоны*\n\n" +
                            "Пока нет шаблонов. Сохрани шаблон из карточки растения.\n\n" +
                            "_⚙️ Настройки растения → 💾 Сохранить как шаблон_")
                    .parseMode("Markdown")
                    .build();
            try {
                client.execute(emptyMessage);
            } catch (TelegramApiException e) {
                log.error("Failed to send empty templates message", e);
            }
            return;
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (PlantTemplate template : templates) {
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text("⭐ " + template.getName() + " (" + template.shortDescription() + ")")
                            .callbackData("TPL_PICK:" + template.getId())
                            .build()
            )));
        }

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text("⭐ *Мои шаблоны*\n\nВыбери шаблон для нового растения:")
                .parseMode("Markdown")
                .replyMarkup(InlineKeyboardMarkup.builder().keyboard(rows).build())
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send templates list", e);
        }
    }

    private void pickTemplate(User user, Long templateId, TelegramClient client) {
        // Валидируем что шаблон существует и принадлежит пользователю
        if (plantTemplateService.getTemplate(user.getId(), templateId).isEmpty()) {
            sendError(user, client);
            return;
        }

        userService.setStateData(user, "template_id", String.valueOf(templateId));
        userService.updateState(user, ConversationState.AWAITING_PLANT_NAME_FROM_TEMPLATE);

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("🌿 Как назвать новое растение?\n\n" +
                        "_Введи имя (от 1 до 100 символов):_")
                .parseMode("Markdown")
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to ask plant name from template", e);
        }
    }

    private void sendError(User user, TelegramClient client) {
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
}
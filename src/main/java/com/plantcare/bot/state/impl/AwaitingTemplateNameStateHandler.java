package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.PlantTemplate;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.PlantTemplateService;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Шаг сохранения шаблона из растения: пользователь вводит имя шаблона.
 *
 * <p>Контекст в stateData: {@code save_template_plant_id} — ID растения,
 * из которого берём активные расписания.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingTemplateNameStateHandler implements StateHandler {

    private final UserService userService;
    private final PlantTemplateService plantTemplateService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_TEMPLATE_NAME;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            sendHint(client, user.getTelegramChatId(),
                    "💾 Введи название шаблона (до 40 символов) или /cancel для отмены.");
            return;
        }

        String raw = update.getMessage().getText().trim();

        try {
            PlantTemplateService.validateName(raw);
        } catch (IllegalArgumentException e) {
            sendHint(client, user.getTelegramChatId(), "❌ " + e.getMessage());
            return;
        }

        Object plantIdRaw = user.getStateData().get("save_template_plant_id");
        if (plantIdRaw == null) {
            log.error("save_template_plant_id missing in stateData for user {}", user.getTelegramChatId());
            userService.resetToIdle(user);
            sendHint(client, user.getTelegramChatId(),
                    "❌ Контекст сохранения утерян. Попробуй снова из карточки растения.");
            return;
        }

        long plantId;
        try {
            plantId = Long.parseLong(String.valueOf(plantIdRaw));
        } catch (NumberFormatException e) {
            log.error("Invalid save_template_plant_id={} for user {}", plantIdRaw, user.getTelegramChatId());
            userService.resetToIdle(user);
            sendHint(client, user.getTelegramChatId(), "❌ Контекст сохранения повреждён.");
            return;
        }

        try {
            PlantTemplate template = plantTemplateService.saveFromPlant(user, plantId, raw);
            userService.resetToIdle(user);
            sendHint(client, user.getTelegramChatId(),
                    "✅ Шаблон *" + template.getName() + "* сохранён!\n\n" +
                    "Найдёшь его в ➕ Добавить растение → ⭐ Из моих шаблонов.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Не сбрасываем state — даём попробовать другое имя
            sendHint(client, user.getTelegramChatId(), "❌ " + e.getMessage());
        }
    }

    private void sendHint(TelegramClient client, Long chatId, String text) {
        try {
            client.execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .parseMode("Markdown")
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send template name hint for chat {}", chatId, e);
        }
    }
}

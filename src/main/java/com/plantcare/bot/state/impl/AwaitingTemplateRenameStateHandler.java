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
 * Шаг переименования шаблона (issue #68: «✏️ Переименовать» в управлении шаблонами).
 *
 * <p>Контекст в stateData: {@code rename_template_id} — ID шаблона.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingTemplateRenameStateHandler implements StateHandler {

    private final UserService userService;
    private final PlantTemplateService plantTemplateService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_TEMPLATE_RENAME;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            sendHint(client, user.getTelegramChatId(),
                    "✏️ Введи новое название шаблона (до 40 символов) или /cancel.");
            return;
        }

        String raw = update.getMessage().getText().trim();

        try {
            PlantTemplateService.validateName(raw);
        } catch (IllegalArgumentException e) {
            sendHint(client, user.getTelegramChatId(), "❌ " + e.getMessage());
            return;
        }

        Object templateIdRaw = user.getStateData().get("rename_template_id");
        if (templateIdRaw == null) {
            log.error("rename_template_id missing in stateData for user {}", user.getTelegramChatId());
            userService.resetToIdle(user);
            sendHint(client, user.getTelegramChatId(),
                    "❌ Контекст переименования утерян. Попробуй снова.");
            return;
        }

        long templateId;
        try {
            templateId = Long.parseLong(String.valueOf(templateIdRaw));
        } catch (NumberFormatException e) {
            log.error("Invalid rename_template_id={} for user {}", templateIdRaw, user.getTelegramChatId());
            userService.resetToIdle(user);
            sendHint(client, user.getTelegramChatId(), "❌ Контекст повреждён.");
            return;
        }

        try {
            PlantTemplate updated = plantTemplateService.renameTemplate(user, templateId, raw);
            userService.resetToIdle(user);
            sendHint(client, user.getTelegramChatId(),
                    "✅ Шаблон переименован в *" + updated.getName() + "*.");
        } catch (IllegalArgumentException e) {
            // Не сбрасываем state — даём ввести другое имя
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
            log.error("Failed to send template rename hint for chat {}", chatId, e);
        }
    }
}

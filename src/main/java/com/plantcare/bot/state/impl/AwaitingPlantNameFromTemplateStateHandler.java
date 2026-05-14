package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.PlantTemplate;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.PlantCardService;
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
 * Шаг создания растения из шаблона: пользователь вводит имя нового растения.
 *
 * <p>Контекст в stateData: {@code template_id} — ID шаблона.
 *
 * <p>После успешного создания шлёт подтверждение «✅ Растение добавлено
 * из шаблона X» (требование issue #68) и вызывает стандартный
 * {@link PlantCardService#finishPlantCreation}, чтобы пользователь видел
 * карточку как при обычном создании.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingPlantNameFromTemplateStateHandler implements StateHandler {

    private static final int PLANT_NAME_MIN_LENGTH = 1;
    private static final int PLANT_NAME_MAX_LENGTH = 100;

    private final UserService userService;
    private final PlantTemplateService plantTemplateService;
    private final PlantCardService plantCardService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_PLANT_NAME_FROM_TEMPLATE;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            sendHint(client, user.getTelegramChatId(),
                    "🌿 Введи имя нового растения или /cancel для отмены.");
            return;
        }

        String rawName = update.getMessage().getText().trim();

        if (rawName.length() < PLANT_NAME_MIN_LENGTH || rawName.length() > PLANT_NAME_MAX_LENGTH) {
            sendHint(client, user.getTelegramChatId(),
                    "❌ Имя растения должно быть от " + PLANT_NAME_MIN_LENGTH +
                    " до " + PLANT_NAME_MAX_LENGTH + " символов.");
            return;
        }

        Object templateIdRaw = user.getStateData().get("template_id");
        if (templateIdRaw == null) {
            log.error("template_id missing in stateData for user {}", user.getTelegramChatId());
            userService.resetToIdle(user);
            sendHint(client, user.getTelegramChatId(),
                    "❌ Контекст утерян. Выбери шаблон заново через ➕ Добавить растение.");
            return;
        }

        long templateId;
        try {
            templateId = Long.parseLong(String.valueOf(templateIdRaw));
        } catch (NumberFormatException e) {
            log.error("Invalid template_id={} for user {}", templateIdRaw, user.getTelegramChatId());
            userService.resetToIdle(user);
            sendHint(client, user.getTelegramChatId(), "❌ Контекст создания повреждён.");
            return;
        }

        try {
            // Берём имя шаблона ДО создания растения, чтобы вывести его в подтверждении
            PlantTemplate template = plantTemplateService.getTemplate(user.getId(), templateId)
                    .orElseThrow(() -> new IllegalArgumentException("Шаблон не найден"));

            Plant plant = plantTemplateService.createPlantFromTemplate(user, templateId, rawName);

            // Подтверждение из issue #68: «✅ Растение добавлено из шаблона {templateName}»
            sendHint(client, user.getTelegramChatId(),
                    "✅ Растение добавлено из шаблона *" + template.getName() + "*");

            // finishPlantCreation покажет карточку + сбросит state + откроет меню
            plantCardService.finishPlantCreation(user, plant, client);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to create plant from template {} for user {}: {}",
                    templateId, user.getTelegramChatId(), e.getMessage());
            userService.resetToIdle(user);
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
            log.error("Failed to send hint for chat {}", chatId, e);
        }
    }
}

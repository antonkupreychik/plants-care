package com.plantcare.bot.state.impl;

import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.bot.service.PlantCardService;
import com.plantcare.core.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Шаг создания черенка (отростка): пользователь вводит имя нового растения-потомка
 * (issue #139, ADR-012).
 *
 * <p>Контекст в stateData: {@code parent_plant_id} — ID материнского растения.
 *
 * <p>Создаёт потомок через {@link PlantCardService#createCutting}, который копирует
 * вид / локацию / интервалы ухода из родителя и проставляет parent_id. После
 * успеха вызывает стандартный {@link PlantCardService#finishPlantCreation},
 * чтобы пользователь прошёл тот же финал, что и при обычном создании.
 *
 * <p>Сделан по образцу {@link AwaitingPlantNameFromTemplateStateHandler}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingCuttingNameStateHandler implements StateHandler {

    private static final int PLANT_NAME_MIN_LENGTH = 1;
    private static final int PLANT_NAME_MAX_LENGTH = 100;

    private final UserService userService;
    private final PlantCardService plantCardService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_CUTTING_NAME;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            sendHint(client, user.getTelegramChatId(),
                    "🌱 Введи имя нового растения или /cancel для отмены.");
            return;
        }

        String rawName = update.getMessage().getText().trim();

        if (rawName.length() < PLANT_NAME_MIN_LENGTH || rawName.length() > PLANT_NAME_MAX_LENGTH) {
            sendHint(client, user.getTelegramChatId(),
                    "❌ Имя растения должно быть от " + PLANT_NAME_MIN_LENGTH +
                    " до " + PLANT_NAME_MAX_LENGTH + " символов.");
            return;
        }

        Object parentIdRaw = user.getStateData().get("parent_plant_id");
        if (parentIdRaw == null) {
            log.error("parent_plant_id missing in stateData for user {}", user.getTelegramChatId());
            userService.resetToIdle(user);
            sendHint(client, user.getTelegramChatId(),
                    "❌ Контекст утерян. Открой карточку растения и нажми «🌱 Взять черенок» заново.");
            return;
        }

        long parentId;
        try {
            parentId = Long.parseLong(String.valueOf(parentIdRaw));
        } catch (NumberFormatException e) {
            log.error("Invalid parent_plant_id={} for user {}", parentIdRaw, user.getTelegramChatId());
            userService.resetToIdle(user);
            sendHint(client, user.getTelegramChatId(), "❌ Контекст создания повреждён.");
            return;
        }

        try {
            Plant cutting = plantCardService.createCutting(user, parentId, rawName);

            sendHint(client, user.getTelegramChatId(),
                    "✅ Черенок *" + cutting.getName() + "* добавлен!");

            // finishPlantCreation покажет карточку + сбросит state + откроет меню
            plantCardService.finishPlantCreation(user, cutting, client);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to create cutting from parent {} for user {}: {}",
                    parentId, user.getTelegramChatId(), e.getMessage());
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

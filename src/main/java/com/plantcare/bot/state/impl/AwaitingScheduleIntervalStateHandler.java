package com.plantcare.bot.state.impl;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.bot.service.PlantCardService;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Map;

/**
 * Шаг редактирования: новый интервал для конкретного типа ухода (issue #27).
 *
 * Использует уже зарезервированный в enum {@link ConversationState#AWAITING_NEW_INTERVAL}.
 * Тип ухода передаётся через stateData → edit_task_type.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingScheduleIntervalStateHandler implements StateHandler {

    private final UserService userService;
    private final PlantService plantService;
    private final PlantCardService plantCardService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_NEW_INTERVAL;
    }

    @Override
    @Transactional
    public void handle(User user, Update update, TelegramClient client) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            sendHint(client, user.getTelegramChatId(),
                    "📅 Пришли число от 1 до 365 или нажми «Отмена».");
            return;
        }

        String raw = update.getMessage().getText().trim();
        int days;
        try {
            days = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            sendHint(client, user.getTelegramChatId(),
                    "❌ Это не число. Попробуй ещё раз (например: 7):");
            return;
        }

        if (!PlantService.isValidInterval(days)) {
            sendHint(client, user.getTelegramChatId(),
                    "❌ Интервал должен быть от 1 до 365 дней.");
            return;
        }

        Map<String, Object> stateData = user.getStateData();
        Long plantId = EditStateData.plantId(stateData);
        String backTarget = EditStateData.backTarget(stateData);
        TaskType taskType = EditStateData.taskType(stateData);

        if (plantId == null || taskType == null) {
            log.error("edit_plant_id={} / edit_task_type={} missing in interval flow for user {}",
                    plantId, taskType, user.getTelegramChatId());
            userService.resetToIdle(user);
            sendHint(client, user.getTelegramChatId(),
                    "❌ Контекст редактирования утерян. Открой карточку и попробуй снова.");
            return;
        }

        try {
            plantService.updateScheduleInterval(user.getId(), plantId, taskType, days);
        } catch (IllegalArgumentException e) {
            sendHint(client, user.getTelegramChatId(), "❌ " + e.getMessage());
            return;
        }

        userService.resetToIdle(user);

        sendHint(client, user.getTelegramChatId(),
                "✅ Интервал обновлён: каждые " + days + " дн.");

        // messageId=null: пришлём свежий экран расписания новым сообщением вниз,
        // чтобы юзер сразу видел результат и мог продолжить (например, сдвинуть
        // ближайшее или вернуться к настройкам).
        plantCardService.showScheduleEditByType(
                user, plantId, taskType, null, backTarget, client);
    }

    private void sendHint(TelegramClient client, Long chatId, String text) {
        try {
            client.execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send interval-edit hint", e);
        }
    }
}

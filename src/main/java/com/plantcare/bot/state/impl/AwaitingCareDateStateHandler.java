package com.plantcare.bot.state.impl;

import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.bot.service.BackdatedCareCallbackService;
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

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Шаг ретро-отметки (issue #118): юзер ввёл «Выбрать дату» в карточке растения,
 * мы перевели его в {@link ConversationState#AWAITING_CARE_DATE}, теперь ждём
 * текст с датой в формате DD.MM или DD.MM.YYYY.
 *
 * <p>Контекст (plantId + taskType) лежит в {@code user.stateData} под ключами
 * {@link BackdatedCareCallbackService#STATE_PLANT_ID} /
 * {@link BackdatedCareCallbackService#STATE_TASK_TYPE} — положил
 * {@code BackdatedCareCallbackService.handleBackPickCustom}.
 *
 * <p>На любом валидном результате (включая «уже отмечено» и «дата в будущем»)
 * сбрасываем стейт в IDLE: повторно вводить дату смысла нет, юзер всегда может
 * заново нажать кнопку в карточке. На невалидном формате — оставляем state,
 * чтобы юзер мог поправить опечатку без возврата в карточку.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingCareDateStateHandler implements StateHandler {

    private final UserService userService;
    private final PlantService plantService;
    private final BackdatedCareCallbackService backdatedCareCallbackService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_CARE_DATE;
    }

    @Override
    @Transactional
    public void handle(User user, Update update, TelegramClient client) {
        Long chatId = user.getTelegramChatId();

        if (!update.hasMessage() || !update.getMessage().hasText()) {
            sendHint(client, chatId,
                    "📅 Пришли дату в формате DD.MM или DD.MM.YYYY.\n"
                    + "Например: 22.05 или 22.05.2026.");
            return;
        }

        Map<String, Object> stateData = user.getStateData();
        Long plantId = parseLong(stateData == null ? null : stateData.get(BackdatedCareCallbackService.STATE_PLANT_ID));
        String taskTypeRaw = stateData == null
                ? null
                : (stateData.get(BackdatedCareCallbackService.STATE_TASK_TYPE) == null
                    ? null
                    : String.valueOf(stateData.get(BackdatedCareCallbackService.STATE_TASK_TYPE)));

        if (plantId == null || taskTypeRaw == null) {
            log.warn("AWAITING_CARE_DATE without context for user {}", chatId);
            userService.resetToIdle(user);
            sendHint(client, chatId, "❌ Контекст утерян. Открой карточку растения и попробуй снова.");
            return;
        }

        TaskType taskType;
        try {
            taskType = TaskType.valueOf(taskTypeRaw);
        } catch (IllegalArgumentException e) {
            userService.resetToIdle(user);
            sendHint(client, chatId, "❌ Внутренняя ошибка типа задачи.");
            return;
        }

        Plant plant = plantService.getPlantForUser(user.getId(), plantId).orElse(null);
        if (plant == null) {
            userService.resetToIdle(user);
            sendHint(client, chatId, "❌ Растение не найдено.");
            return;
        }

        String raw = update.getMessage().getText().trim();
        Optional<LocalDate> parsed = backdatedCareCallbackService.parseCareDate(raw, user);
        if (parsed.isEmpty()) {
            // Намеренно НЕ сбрасываем state — даём юзеру переввести.
            sendHint(client, chatId,
                    "❌ Не понял дату. Формат: DD.MM или DD.MM.YYYY.\n"
                    + "Например: 22.05 или 22.05.2026.");
            return;
        }

        // Валидные пути (CREATED / ALREADY_RECORDED / IN_FUTURE / TOO_OLD) делегируем
        // в общий applyResult — он формирует текст ответа единым образом для всех flow.
        backdatedCareCallbackService.applyResult(
                user, plant, taskType, parsed.get(), chatId, null, null, client);

        // Любой валидный исход сбрасывает стейт: ввод выполнил свою задачу.
        userService.resetToIdle(user);
    }

    private void sendHint(TelegramClient client, Long chatId, String text) {
        try {
            client.execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send care-date hint: {}", e.getMessage(), e);
        }
    }

    private Long parseLong(Object raw) {
        if (raw == null) return null;
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }

}

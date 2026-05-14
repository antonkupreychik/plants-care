package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.state.interfaces.StateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Блокирующий шаг wizard'а создания растения: «Это новое растение?» (issue #75).
 *
 * <p>Сами ответы (callback'и {@code PLANT:ACCL:YES} / {@code PLANT:ACCL:NO})
 * маршрутизируются через {@code MenuCallbackService} — там есть полный контекст
 * и удобные сервисы. Этот handler нужен только для одного случая: если юзер
 * вместо кнопки прислал текст — подсказать, что нужно нажать кнопку.
 *
 * <p>Callback'и сюда не приходят: бот {@code PlantsCareBot} маршрутизирует
 * {@code PLANT:*} в menu-callback ДО state-резолвера.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingPlantAcclimationChoiceStateHandler implements StateHandler {

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_PLANT_ACCLIMATION_CHOICE;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        // Игнорируем callbacks — они уже обработаны в MenuCallbackService.
        if (update.hasCallbackQuery()) {
            return;
        }

        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        // Текст вместо кнопки — мягкий хинт. State не сбрасываем, кнопки остаются
        // в исходном сообщении выше; юзер просто нажмёт «Да» или «Нет».
        SendMessage hint = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("☝️ Сначала ответь на вопрос выше — кнопкой «✅ Да» или «❌ Нет».")
                .build();
        try {
            client.execute(hint);
        } catch (TelegramApiException e) {
            log.error("Failed to send acclimation-choice hint: {}", e.getMessage(), e);
        }
    }
}

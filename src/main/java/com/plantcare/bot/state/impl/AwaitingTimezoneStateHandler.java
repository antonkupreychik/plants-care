package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.iakovlev.timeshape.TimeZoneEngine;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.location.Location;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingTimezoneStateHandler implements StateHandler {
    private final UserService userService;
    private final TimeZoneEngine engine;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_TIMEZONE;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        Long chatId = user.getTelegramChatId();

        if (update.hasMessage() && update.getMessage().hasLocation()) {
            Location loc = update.getMessage().getLocation();
            engine.query(loc.getLatitude(), loc.getLongitude())
                    .ifPresentOrElse(
                            zone -> saveAndFinish(user, zone.getId(), client),
                            () -> sendError(chatId, client)
                    );
            return;
        }

        if (update.hasMessage() && "⌨️ Выбрать вручную".equals(update.getMessage().getText())) {
            showManualTimezonePicker(chatId, client);
            userService.updateState(user, ConversationState.AWAITING_TIMEZONE_MANUAL);
            return;
        }
    }

    private void sendError(Long chatId, TelegramClient client) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text("❌ К сожалению, я не смог определить часовой пояс по этим координатам. " +
                        "Попробуй выбрать город вручную из списка ниже.")
                .build();

        try {
            client.execute(message);
            showManualTimezonePicker(chatId, client);
        } catch (TelegramApiException e) {
            log.error("Failed to send error message in onboarding", e);
        }
    }

    private void saveAndFinish(User user, String timezoneId, TelegramClient client) {
        user.setTimezone(timezoneId);
        log.info("Timezone set to {} for user {}", timezoneId, user.getTelegramChatId());
        userService.updateState(user, ConversationState.AWAITING_PLANT_NAME);

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("✅ Часовой пояс установлен: *" + timezoneId + "*.\n\n" +
                        "Теперь давай добавим твое первое растение. Как оно называется?")
                .parseMode("Markdown")
                .replyMarkup(new ReplyKeyboardRemove(true)) // Убираем кнопку локации
                .build();
        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send success message in onboarding", e);
        }
    }

    private void showManualTimezonePicker(Long chatId, TelegramClient client) {
        List<String> popular = List.of("Europe/Moscow", "Europe/Kyiv", "Asia/Almaty",
                "Europe/Berlin", "Europe/London", "America/New_York");

        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder<?, ?> markup = InlineKeyboardMarkup.builder();

        popular.forEach(zone -> {
            InlineKeyboardButton button = InlineKeyboardButton.builder()
                    .text(zone)
                    .callbackData("SET_TZ:" + zone) // Префикс для обработки в будущем
                    .build();

            markup.keyboardRow(new InlineKeyboardRow(button));
        });

        try {
            client.execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text("Выбери свой город или ближайший к тебе:")
                    .replyMarkup(markup.build())
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send manual timezone picker to chatId: {}", chatId, e);
        }
    }
}
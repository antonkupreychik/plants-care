package com.plantcare.bot.state.impl;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.service.UserService;
import com.plantcare.core.service.UserSettingsService;
import com.plantcare.core.service.UserSettingsService.TimezoneChangeResult;
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

import java.time.ZoneId;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingTimezoneStateHandler implements StateHandler {

    private final UserService userService;
    private final UserSettingsService userSettingsService;
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

        if (update.hasMessage()
                && update.getMessage().hasText()
                && "⌨️ Выбрать вручную".equals(update.getMessage().getText())) {
            showManualTimezonePicker(chatId, client);
            userService.updateState(user, ConversationState.AWAITING_TIMEZONE_MANUAL);
            return;
        }

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text("Отправь локацию или нажми «⌨️ Выбрать вручную».")
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send timezone help message", e);
        }
    }

    private void sendError(Long chatId, TelegramClient client) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text("❌ Не смог определить часовой пояс по этим координатам. Попробуй выбрать город вручную.")
                .build();

        try {
            client.execute(message);
            showManualTimezonePicker(chatId, client);
        } catch (TelegramApiException e) {
            log.error("Failed to send error message in timezone setup", e);
        }
    }

    private void saveAndFinish(User user, String timezoneId, TelegramClient client) {
        TimezoneChangeResult result = userSettingsService.changeTimezone(user, ZoneId.of(timezoneId));
        userService.updateState(user, ConversationState.IDLE);

        String text = UserSettingsService.buildTimezoneConfirmation(result, user.getQuietHoursEnd())
                + "\n\nОткрой /menu, чтобы продолжить.";

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(text)
                .replyMarkup(new ReplyKeyboardRemove(true))
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send timezone success message", e);
        }
    }

    private void showManualTimezonePicker(Long chatId, TelegramClient client) {
        List<String> popular = List.of(
                "Europe/Minsk",
                "Europe/Moscow",
                "Europe/Kyiv",
                "Asia/Almaty",
                "Asia/Tbilisi",
                "Europe/Warsaw",
                "Europe/Berlin"
        );

        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder<?, ?> markup = InlineKeyboardMarkup.builder();

        popular.forEach(zone -> {
            InlineKeyboardButton button = InlineKeyboardButton.builder()
                    .text(zone)
                    .callbackData("SET_TZ:" + zone)
                    .build();

            markup.keyboardRow(new InlineKeyboardRow(button));
        });

        markup.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                .text("🌍 Другая (ввести вручную)")
                .callbackData("SET_TZ_CUSTOM")
                .build()));

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text("Выбери свой регион или ближайший к тебе:")
                .replyMarkup(markup.build())
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send manual timezone picker to chatId: {}", chatId, e);
        }
    }
}
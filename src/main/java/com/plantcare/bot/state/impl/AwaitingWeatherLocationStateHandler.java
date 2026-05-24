package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.repository.UserRepository;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.location.Location;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Состояние «ждём локацию для погоды» (issue #69). Юзер пришёл сюда через
 * экран настроек → «📍 Указать локацию». Поведение:
 * <ul>
 *   <li>пришла location-message → сохраняем lat/lon в users, переводим в IDLE,
 *       ответ «✅ Локация сохранена…»;</li>
 *   <li>пришёл текст «❌ Отмена» → сбрасываем state в IDLE, отвечаем «Отменено»;</li>
 *   <li>любой другой контент → мягкое напоминание, что нужно сделать.</li>
 * </ul>
 *
 * <p>В отличие от {@code AwaitingTimezoneStateHandler}, мы НЕ резолвим TZ по
 * координатам — у юзера уже есть свой timezone, погода берётся по lat/lon
 * напрямую.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingWeatherLocationStateHandler implements StateHandler {

    private final UserService userService;
    private final UserRepository userRepository;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_WEATHER_LOCATION;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        Long chatId = user.getTelegramChatId();

        if (update.hasMessage() && update.getMessage().hasLocation()) {
            Location loc = update.getMessage().getLocation();
            saveAndFinish(user, loc.getLatitude(), loc.getLongitude(), client);
            return;
        }

        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText();
            if ("❌ Отмена".equals(text)) {
                userService.updateState(user, ConversationState.IDLE);
                send(chatId, client, "Отменено.", new ReplyKeyboardRemove(true));
                return;
            }
        }

        // Любой другой ввод — re-prompt.
        send(chatId, client,
                "Отправь локацию через кнопку «📍 Отправить локацию» "
                        + "или нажми «❌ Отмена».",
                null);
    }

    private void saveAndFinish(User user, double lat, double lon, TelegramClient client) {
        user.setWeatherLat(lat);
        user.setWeatherLon(lon);
        userRepository.save(user);
        userService.updateState(user, ConversationState.IDLE);

        log.info("Weather location set for user {}: lat={}, lon={}",
                user.getTelegramChatId(), lat, lon);

        send(user.getTelegramChatId(), client,
                "✅ Локация сохранена. Буду учитывать влажность в рекомендациях.",
                new ReplyKeyboardRemove(true));
    }

    private void send(Long chatId, TelegramClient client, String text,
                      ReplyKeyboardRemove removeKeyboard) {
        SendMessage.SendMessageBuilder<?, ?> builder = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text);
        if (removeKeyboard != null) builder.replyMarkup(removeKeyboard);

        try {
            client.execute(builder.build());
        } catch (TelegramApiException e) {
            log.error("Failed to send weather-location reply to {}: {}",
                    chatId, e.getMessage(), e);
        }
    }
}

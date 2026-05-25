package com.plantcare.core.weather.service;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.core.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

/**
 * Экран «🌦 Погода» в настройках и связанные действия (issue #69).
 *
 * <p>{@link #renderScreen} умеет и слать новое сообщение, и редактировать
 * существующее по {@code messageId}. На toggle мы редактируем то же
 * сообщение — иначе у юзера в чате растёт серия одинаковых сообщений
 * и кажется, что «кнопки не работают» (нажатие не визуализировано
 * там, где ожидалось).
 *
 * <p>Текст рендерим plain (без Markdown) — он простой, без форматирования,
 * и так не зависит от спецсимволов в координатах.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WeatherMenuService {

    private final UserRepository userRepository;
    private final UserService userService;

    /** Точка входа из «MENU:WEATHER» — здесь messageId известен. */
    public void sendWeatherScreen(User user, Integer messageId, TelegramClient client) {
        renderScreen(user, messageId, client);
    }

    /** Совместимая перегрузка для мест, где messageId недоступен. */
    public void sendWeatherScreen(User user, TelegramClient client) {
        renderScreen(user, null, client);
    }

    @Transactional
    public void toggleEnabled(User user, Integer messageId, TelegramClient client) {
        // Лог СНАЧАЛА — чтобы видеть факт вызова даже если save() упадёт.
        boolean newValue = !user.isWeatherEnabled();
        log.info("Weather toggle requested: user={}, current={}, target={}, messageId={}",
                user.getTelegramChatId(), user.isWeatherEnabled(), newValue, messageId);
        user.setWeatherEnabled(newValue);
        userRepository.save(user);
        log.info("Weather toggle persisted for user {}", user.getTelegramChatId());
        renderScreen(user, messageId, client);
    }

    /**
     * Просим юзера прислать локацию. Тут НЕЛЬЗЯ через EditMessageText —
     * нам нужна ReplyKeyboardMarkup (с requestLocation), а её можно
     * показать только через новое сообщение SendMessage.
     */
    public void promptForLocation(User user, TelegramClient client) {
        userService.updateState(user, ConversationState.AWAITING_WEATHER_LOCATION);

        ReplyKeyboardMarkup keyboard = ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow(List.of(
                        KeyboardButton.builder()
                                .text("📍 Отправить локацию")
                                .requestLocation(true)
                                .build()
                )))
                .keyboardRow(new KeyboardRow(List.of(
                        new KeyboardButton("❌ Отмена")
                )))
                .resizeKeyboard(true)
                .oneTimeKeyboard(true)
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("Нажми \"📍 Отправить локацию\" — буду брать влажность из Open-Meteo по этой точке.")
                .replyMarkup(keyboard)
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send weather location prompt: {}", e.getMessage(), e);
        }
    }

    private void renderScreen(User user, Integer messageId, TelegramClient client) {
        String text = buildText(user);
        InlineKeyboardMarkup keyboard = buildKeyboard(user);

        if (messageId != null) {
            EditMessageText edit = EditMessageText.builder()
                    .chatId(user.getTelegramChatId().toString())
                    .messageId(messageId)
                    .text(text)
                    .replyMarkup(keyboard)
                    .build();
            try {
                client.execute(edit);
                log.info("Weather screen edited (messageId={}) for user {}",
                        messageId, user.getTelegramChatId());
                return;
            } catch (TelegramApiException e) {
                // Если редактирование упало (например, сообщение слишком старое),
                // мягко падаем на новое сообщение — лучше дубль чем мёртвая кнопка.
                log.warn("Failed to edit weather screen (messageId={}), sending new instead: {} ({})",
                        messageId, e.getMessage(), e.getClass().getSimpleName());
            }
        }

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(text)
                .replyMarkup(keyboard)
                .build();
        try {
            client.execute(message);
            log.info("Weather screen sent (new message) to user {}", user.getTelegramChatId());
        } catch (TelegramApiException e) {
            log.error("Failed to send weather screen to user {}: {} ({})",
                    user.getTelegramChatId(), e.getMessage(), e.getClass().getSimpleName(), e);
        }
    }

    private String buildText(User user) {
        StringBuilder sb = new StringBuilder("🌦 Погода\n\n");
        sb.append("Подсказки про влажность за окном для задач полива.\n\n");

        sb.append("Статус: ")
                .append(user.isWeatherEnabled() ? "✅ Включено" : "⏸ Выключено")
                .append("\n");

        sb.append("Локация: ");
        if (user.hasWeatherLocation()) {
            sb.append("✅ задана (")
                    .append(String.format("%.2f, %.2f",
                            user.getWeatherLat(), user.getWeatherLon()))
                    .append(")\n");
        } else {
            sb.append("⚠️ не задана\n");
        }

        if (user.isWeatherEnabled() && !user.hasWeatherLocation()) {
            sb.append("\nЧтобы подсказки заработали — нажми \"📍 Указать локацию\".");
        }
        if (!user.isWeatherEnabled() && user.hasWeatherLocation()) {
            sb.append("\nЛокация сохранена, но подсказки сейчас выключены.");
        }
        if (user.isWeatherUsable()) {
            sb.append("\nБот будет добавлять строку про влажность в подсказки полива.");
        }
        return sb.toString();
    }

    private InlineKeyboardMarkup buildKeyboard(User user) {
        InlineKeyboardButton toggle = InlineKeyboardButton.builder()
                .text(user.isWeatherEnabled() ? "⏸ Выключить" : "✅ Включить")
                .callbackData("WEATHER:TOGGLE")
                .build();

        InlineKeyboardButton location = InlineKeyboardButton.builder()
                .text(user.hasWeatherLocation()
                        ? "📍 Изменить локацию"
                        : "📍 Указать локацию")
                .callbackData("WEATHER:LOCATION")
                .build();

        InlineKeyboardButton back = InlineKeyboardButton.builder()
                .text("⬅️ Назад в настройки")
                .callbackData("MENU:SETTINGS")
                .build();

        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(toggle)))
                .keyboardRow(new InlineKeyboardRow(List.of(location)))
                .keyboardRow(new InlineKeyboardRow(List.of(back)))
                .build();
    }
}

package com.plantcare.bot.service;

import com.plantcare.core.service.DiseaseCard;
import com.plantcare.core.service.DiseaseNotFoundException;
import com.plantcare.core.service.DiseaseService;
import com.plantcare.core.service.UserService;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

/**
 * Раздел «🦠 Болезни» пользовательского справочника (issue #140, ADR-013).
 *
 * <p>Тонкий Telegram-слой: парсит/рендерит, бизнес-логику делегирует
 * {@link DiseaseService}. Репозиториями напрямую не пользуется.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiseaseMenuService {

    public static final String LIST_CALLBACK = "DISEASE:LIST";
    public static final String SEARCH_CALLBACK = "DISEASE:SEARCH";
    public static final String VIEW_PREFIX = "DISEASE:VIEW:";

    private static final int MAX_SEARCH_RESULTS = 10;

    private final DiseaseService diseaseService;
    private final UserService userService;

    /**
     * Список всех болезней справочника + кнопка поиска по симптому/тексту.
     */
    public void sendList(User user, TelegramClient client) {
        // Справочник — это просмотр, а не шаг визарда: гасим возможный
        // незавершённый AWAITING_DISEASE_SEARCH, чтобы текст не уходил в поиск.
        userService.resetToIdle(user);

        List<DiseaseCard> diseases = diseaseService.getAll();

        String text = """
                🦠 *Болезни и вредители*

                Справочник распространённых проблем комнатных растений.
                Выбери из списка или найди по симптому/названию.

                _Это справочная информация, а не медицинский диагноз._""";

        sendMarkdown(user.getTelegramChatId(), text, buildListKeyboard(diseases), client);
    }

    /**
     * Переводит пользователя в режим текстового поиска по болезням.
     */
    public void startSearch(User user, TelegramClient client) {
        userService.updateState(user, ConversationState.AWAITING_DISEASE_SEARCH);

        sendText(
                user.getTelegramChatId(),
                "🔍 Напиши симптом или название проблемы.\n\n"
                        + "Например: «жёлтые листья», «паутинка», «пятна».",
                client
        );
    }

    /**
     * Поиск болезней по тексту запроса и показ результатов.
     */
    public void sendSearchResults(User user, String query, TelegramClient client) {
        List<DiseaseCard> results = diseaseService.search(query, MAX_SEARCH_RESULTS);

        if (results.isEmpty()) {
            sendMarkdown(
                    user.getTelegramChatId(),
                    "🔍 Ничего не нашёл по запросу «" + escapeMarkdown(query) + "».\n\n"
                            + "Попробуй другой симптом или открой полный список.",
                    InlineKeyboardMarkup.builder()
                            .keyboardRow(new InlineKeyboardRow(List.of(
                                    button("📋 Все болезни", LIST_CALLBACK)
                            )))
                            .build(),
                    client
            );
            return;
        }

        log.debug("Disease search results sent. chatId={}, query='{}', found={}",
                user.getTelegramChatId(), query, results.size());

        sendMarkdown(
                user.getTelegramChatId(),
                "🦠 Нашёл: " + results.size() + ". Выбери проблему:",
                buildResultsKeyboard(results),
                client
        );
    }

    /**
     * Карточка болезни: симптомы / лечение / профилактика.
     */
    public void sendCard(User user, Long diseaseId, TelegramClient client) {
        userService.resetToIdle(user);

        DiseaseCard card;

        try {
            card = diseaseService.getById(diseaseId);
        } catch (DiseaseNotFoundException e) {
            sendText(user.getTelegramChatId(), "❌ Болезнь не найдена.", client);
            return;
        }

        sendMarkdown(user.getTelegramChatId(), buildCardText(card), buildCardKeyboard(), client);
    }

    private String buildCardText(DiseaseCard card) {
        StringBuilder text = new StringBuilder();

        text.append("🦠 *").append(escapeMarkdown(card.name())).append("*\n");

        if (card.latinName() != null && !card.latinName().isBlank()) {
            text.append("_").append(escapeMarkdown(card.latinName())).append("_\n");
        }

        text.append("\n*Симптомы*\n").append(escapeMarkdown(card.symptoms())).append("\n");
        text.append("\n*Что делать*\n").append(escapeMarkdown(card.treatment())).append("\n");
        text.append("\n*Профилактика*\n").append(escapeMarkdown(card.prevention())).append("\n");

        text.append("\n_Справочная информация, а не диагноз._");

        return text.toString();
    }

    private InlineKeyboardMarkup buildListKeyboard(List<DiseaseCard> diseases) {
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder<?, ?> builder = InlineKeyboardMarkup.builder();

        builder.keyboardRow(new InlineKeyboardRow(List.of(
                button("🔍 Найти по симптому", SEARCH_CALLBACK)
        )));

        for (DiseaseCard disease : diseases) {
            builder.keyboardRow(new InlineKeyboardRow(List.of(
                    button(disease.name(), VIEW_PREFIX + disease.id())
            )));
        }

        return builder.build();
    }

    private InlineKeyboardMarkup buildResultsKeyboard(List<DiseaseCard> diseases) {
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder<?, ?> builder = InlineKeyboardMarkup.builder();

        for (DiseaseCard disease : diseases) {
            builder.keyboardRow(new InlineKeyboardRow(List.of(
                    button(disease.name(), VIEW_PREFIX + disease.id())
            )));
        }

        builder.keyboardRow(new InlineKeyboardRow(List.of(
                button("📋 Все болезни", LIST_CALLBACK)
        )));

        return builder.build();
    }

    private InlineKeyboardMarkup buildCardKeyboard() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        button("⬅️ К списку болезней", LIST_CALLBACK)
                )))
                .build();
    }

    private InlineKeyboardButton button(String text, String callbackData) {
        return InlineKeyboardButton.builder()
                .text(text)
                .callbackData(callbackData)
                .build();
    }

    private void sendText(Long chatId, String text, TelegramClient client) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send disease text message", e);
        }
    }

    private void sendMarkdown(Long chatId, String text, ReplyKeyboard keyboard, TelegramClient client) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("Markdown")
                .replyMarkup(keyboard)
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send disease markdown message", e);
        }
    }

    private String escapeMarkdown(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("_", "\\_")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("`", "\\`");
    }
}

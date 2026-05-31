package com.plantcare.bot.state.impl;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.bot.service.DiseaseMenuService;
import com.plantcare.bot.state.interfaces.StateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Поиск по справочнику болезней (issue #140): пользователь ввёл текст симптома/названия.
 *
 * <p>Тонкий слой — делегирует {@link DiseaseMenuService}, который дёргает
 * {@code DiseaseService} (FTS) и рендерит результаты. Callback'и {@code DISEASE:*}
 * сюда не доходят: они перехватываются раньше в {@code PlantsCareBot} по префиксу.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingDiseaseSearchStateHandler implements StateHandler {

    private final DiseaseMenuService diseaseMenuService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_DISEASE_SEARCH;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String query = update.getMessage().getText().trim();

        if (query.isEmpty()) {
            return;
        }

        log.debug("Disease search query from chatId={}: '{}'", user.getTelegramChatId(), query);

        diseaseMenuService.sendSearchResults(user, query, client);
    }
}

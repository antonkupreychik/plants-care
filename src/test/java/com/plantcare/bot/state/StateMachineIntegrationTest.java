package com.plantcare.bot.state;

import com.plantcare.bot.beans.PlantsCareBot;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.repository.UserRepository;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Интеграционные тесты механизма состояний")
class StateMachineIntegrationTest extends IntegrationTestBase {

    @Autowired private PlantsCareBot plantsCareBot;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;

    @Test
    @DisplayName("Цепочка: переход из IDLE в состояние ожидания и успешный сброс в IDLE")
    void shouldHandleStateTransitionFlow() {
        Long chatId = 777L;
        String username = "test_user";

        // 1. Предварительное условие: Юзер в IDLE
        User user = userService.findOrCreate(chatId, username);
        assertThat(user.getConversationState()).isEqualTo(ConversationState.IDLE);

        // 2. Имитируем команду, которая переводит юзера в AWAITING_ROOM_NAME
        // (Предположим, у нас есть команда /add_room, которая это делает)
        userService.updateState(user, ConversationState.AWAITING_ROOM_NAME);

        // 3. Посылаем Update, который должен обработаться StateResolver-ом
        Update update = createUpdate(chatId, "Гостиная");
        plantsCareBot.consume(update);

        // 4. Проверяем результат
        User updatedUser = userRepository.findByTelegramChatId(chatId).orElseThrow();

        // Состояние должно вернуться в IDLE (как в примере RoomNameStateHandler)
        assertThat(updatedUser.getConversationState()).isEqualTo(ConversationState.IDLE);
        // Данные должны быть сохранены в state_data
        assertThat(updatedUser.getStateData()).containsEntry("temp_room_name", "Гостиная");
    }

    @Test
    @DisplayName("Команда /cancel должна сбрасывать состояние и очищать данные")
    void shouldResetStateOnCancelCommand() {
        Long chatId = 888L;
        User user = userService.findOrCreate(chatId, "cancel_user");

        // Устанавливаем грязное состояние и данные
        userService.updateState(user, ConversationState.AWAITING_PLANT_NAME);
        userService.setStateData(user, "key", "value");

        // Посылаем /cancel
        Update update = createUpdate(chatId, "/cancel");
        plantsCareBot.consume(update);

        // Проверяем
        User resetUser = userRepository.findByTelegramChatId(chatId).orElseThrow();
        assertThat(resetUser.getConversationState()).isEqualTo(ConversationState.IDLE);
        assertThat(resetUser.getStateData()).isEmpty();
    }

    private Update createUpdate(Long chatId, String text) {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        org.telegram.telegrambots.meta.api.objects.User telegramUser = mock(org.telegram.telegrambots.meta.api.objects.User.class);

        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(chatId);
        when(message.getText()).thenReturn(text);
        when(message.getFrom()).thenReturn(telegramUser);
        when(telegramUser.getUserName()).thenReturn("test_user");
        return update;
    }
}
package com.plantcare.bot.repository;

import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class UserRepositoryTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void cleanup() {
        userRepository.deleteAll();
    }

    @Test
    void savesAndLoadsUserWithDefaults() {
        User saved = userRepository.save(User.builder()
                .telegramChatId(100L)
                .username("alice")
                .build());

        Optional<User> loaded = userRepository.findById(saved.getId());

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getTimezone()).isEqualTo("Europe/Minsk");
        assertThat(loaded.get().getConversationState()).isEqualTo(ConversationState.IDLE);
        assertThat(loaded.get().isBlocked()).isFalse();
        assertThat(loaded.get().getCreatedAt()).isNotNull();
    }

    @Test
    void findByTelegramChatIdReturnsCorrectUser() {
        userRepository.save(User.builder().telegramChatId(101L).build());
        userRepository.save(User.builder().telegramChatId(102L).build());

        assertThat(userRepository.findByTelegramChatId(101L)).isPresent();
        assertThat(userRepository.findByTelegramChatId(999L)).isEmpty();
    }

    @Test
    void existsByTelegramChatIdWorks() {
        userRepository.save(User.builder().telegramChatId(103L).build());

        assertThat(userRepository.existsByTelegramChatId(103L)).isTrue();
        assertThat(userRepository.existsByTelegramChatId(404L)).isFalse();
    }

    @Test
    void jsonbStateDataPersistsCorrectly() {
        // Это критичный тест: state_data — JSONB колонка, важно убедиться что
        // hypersistence-utils корректно сериализует и читает Map.
        Map<String, Object> data = new HashMap<>();
        data.put("species_id", 42);
        data.put("plant_name", "Монстера");
        data.put("interval_days", 7);

        User user = userRepository.save(User.builder()
                .telegramChatId(104L)
                .conversationState(ConversationState.AWAITING_PLANT_NAME)
                .stateData(data)
                .build());

        // Полный round-trip через flush + clear, чтобы достать из БД, а не из persistence context
        userRepository.flush();

        User reloaded = userRepository.findById(user.getId()).orElseThrow();

        assertThat(reloaded.getStateData())
                .containsEntry("species_id", 42)
                .containsEntry("plant_name", "Монстера")
                .containsEntry("interval_days", 7);
        assertThat(reloaded.getConversationState()).isEqualTo(ConversationState.AWAITING_PLANT_NAME);
    }

    @Test
    void resetConversationClearsStateAndData() {
        User user = userRepository.save(User.builder()
                .telegramChatId(105L)
                .conversationState(ConversationState.AWAITING_PLANT_NAME)
                .stateData(Map.of("plant_name", "Фикус"))
                .build());

        user.resetConversation();
        userRepository.save(user);

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getConversationState()).isEqualTo(ConversationState.IDLE);
        assertThat(reloaded.getStateData()).isEmpty();
    }
}

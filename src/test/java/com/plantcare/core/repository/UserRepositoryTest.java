package com.plantcare.core.repository;

import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.User;
import com.plantcare.bot.support.IntegrationTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

    @Autowired
    private LocationRepository locationRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void cleanup() {
        // Локации ссылаются на users по FK — удаляем их первыми, иначе deleteAll
        // по users падает на нарушении внешнего ключа active_location_id/user_id.
        locationRepository.deleteAll();
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
    void findByTelegramChatIdEagerlyLoadsActiveLocation() {
        // Регрессия на no Session в /menu: @EntityGraph должен инициализировать
        // ленивый activeLocation в транзакции загрузки. Чистим контекст, чтобы
        // прокси нельзя было дочитать через сессию — читаем именно то, что
        // подтянул граф.
        User user = userRepository.save(User.builder().telegramChatId(106L).build());
        Location location = locationRepository.save(Location.builder()
                .user(user)
                .name(Location.DEFAULT_NAME)
                .emoji(Location.DEFAULT_EMOJI)
                .defaultLocation(true)
                .build());
        user.setActiveLocation(location);
        userRepository.saveAndFlush(user);
        entityManager.clear();

        User loaded = userRepository.findByTelegramChatId(106L).orElseThrow();

        // Доступ к полю activeLocation вне активной сессии не должен кидать
        // LazyInitializationException.
        assertThat(loaded.getActiveLocation()).isNotNull();
        assertThat(loaded.getActiveLocation().getDisplayName())
                .isEqualTo(Location.DEFAULT_EMOJI + " " + Location.DEFAULT_NAME);
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

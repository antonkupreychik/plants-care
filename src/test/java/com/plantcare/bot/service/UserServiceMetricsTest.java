package com.plantcare.bot.service;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.metrics.MetricsService;
import com.plantcare.bot.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.util.Optional;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты на инструментирование {@link UserService} метриками (issue #115).
 *
 * <p>Ключевой инвариант — {@code recordUserRegistered()} должен вызываться
 * РОВНО на одно реальное создание пользователя, а не на каждый {@code /start}
 * от уже зарегистрированного. Это значит, что {@code users_registered_total}
 * в Prometheus отражает реальный рост базы, а не общую активность.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserService — recordUserRegistered (#115)")
class UserServiceMetricsTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private MetricsService metricsService;

    @Mock
    private Clock clock;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("Новый юзер → recordUserRegistered() вызывается один раз")
    void should_record_user_registered_when_user_is_new() {
        Long chatId = 555L;
        User created = User.builder().telegramChatId(chatId).username("alice").build();

        // existsByTelegramChatId возвращает false → юзер новый.
        when(userRepository.existsByTelegramChatId(chatId)).thenReturn(false);
        when(userRepository.findByTelegramChatId(chatId)).thenReturn(Optional.of(created));

        userService.findOrCreate(chatId, "alice");

        verify(metricsService).recordUserRegistered();
    }

    @Test
    @DisplayName("Существующий юзер → recordUserRegistered() НЕ вызывается")
    void should_not_record_user_registered_when_user_already_exists() {
        Long chatId = 555L;
        User existing = User.builder().telegramChatId(chatId).username("alice").build();

        when(userRepository.existsByTelegramChatId(chatId)).thenReturn(true);
        when(userRepository.findByTelegramChatId(chatId)).thenReturn(Optional.of(existing));

        userService.findOrCreate(chatId, "alice");

        verify(metricsService, never()).recordUserRegistered();
    }

    @Test
    @DisplayName("Повторные /start от того же юзера → recordUserRegistered один раз за всё время")
    void should_record_user_registered_only_on_first_start() {
        Long chatId = 777L;
        User created = User.builder().telegramChatId(chatId).username("bob").build();

        // Первый вызов — юзера нет.
        when(userRepository.existsByTelegramChatId(chatId))
                .thenReturn(false)
                .thenReturn(true)
                .thenReturn(true);
        when(userRepository.findByTelegramChatId(chatId)).thenReturn(Optional.of(created));

        userService.findOrCreate(chatId, "bob");
        userService.findOrCreate(chatId, "bob");
        userService.findOrCreate(chatId, "bob");

        verify(metricsService, org.mockito.Mockito.times(1)).recordUserRegistered();
    }
}

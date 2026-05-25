package com.plantcare.api;

import com.plantcare.core.domain.User;
import com.plantcare.core.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Вспомогательный компонент: резолвит {@link User} по Telegram chat id
 * для всех контроллеров публичного REST API.
 *
 * <p>Централизует логику «нашли / не нашли», чтобы не дублировать её
 * в каждом контроллере.
 */
@Component
@RequiredArgsConstructor
public class UserApiResolver {

    private final UserRepository userRepository;

    /**
     * Возвращает пользователя по {@code telegramChatId} или бросает
     * {@link EntityNotFoundException} (→ 404), если такого нет в БД.
     */
    public User resolve(Long chatId) {
        return userRepository.findByTelegramChatId(chatId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "User not found for chatId: " + chatId));
    }
}

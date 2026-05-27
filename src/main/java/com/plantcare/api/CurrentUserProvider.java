package com.plantcare.api;

import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.api.config.AuthProperties;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Резолвит текущего пользователя из JWT в {@link SecurityContextHolder}
 * (issue #88). Claim {@code sub} access-токена = {@code users.id}.
 *
 * <p>Заменяет прежнюю идентификацию по заголовкам {@code X-User-Id} /
 * {@code X-Chat-Id}. Контроллеры берут отсюда либо {@link #currentUserId()},
 * либо полноценного {@link #currentUser()}.
 */
@Component
@RequiredArgsConstructor
public class CurrentUserProvider {

    private final UserRepository userRepository;
    private final AuthProperties authProperties;

    /** {@code users.id} текущего аутентифицированного пользователя из claim {@code sub}. */
    public Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            // Локальная разработка: авторизация выключена (plantcare.auth.enabled=false),
            // /api/v1/** открыты без токена — работаем от имени фиксированного dev-юзера.
            if (!authProperties.isEnabled()) {
                return authProperties.getDevUserId();
            }
            throw AuthTokenException.invalid("No authenticated user in security context");
        }
        String subject = jwt.getSubject();
        if (subject == null) {
            throw AuthTokenException.invalid("Token has no subject");
        }
        try {
            return Long.parseLong(subject);
        } catch (NumberFormatException e) {
            throw AuthTokenException.invalid("Token subject is not a user id", e);
        }
    }

    /** Загружает текущего {@link User} из БД или бросает 404, если запись исчезла. */
    public User currentUser() {
        Long userId = currentUserId();
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));
    }
}

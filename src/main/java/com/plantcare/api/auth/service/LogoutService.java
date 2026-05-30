package com.plantcare.api.auth.service;

import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.core.repository.RevokedRefreshTokenRepository;
import com.plantcare.core.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

/**
 * Выход из сессии (issue #178).
 *
 * <ul>
 *   <li>{@code logout} — отзывает предъявленный refresh-токен текущего
 *       пользователя. Толерантен: невалидный/чужой/просроченный токен молча
 *       игнорируется, повторный logout идемпотентен (rowcount не проверяем).</li>
 *   <li>{@code logoutAll} — выставляет эпоху {@code tokens_valid_from = now},
 *       одномоментно инвалидируя все ранее выданные refresh-токены юзера.</li>
 * </ul>
 *
 * <p>Внешних HTTP-вызовов в транзакции нет — всё локально в БД.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogoutService {

    private final TokenService tokenService;
    private final RevokedRefreshTokenRepository revokedRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /**
     * Отзывает refresh-токен текущего пользователя. Если токен не парсится,
     * просрочен или принадлежит другому пользователю — ничего не делает.
     * Всегда завершается без исключения (контроллер отвечает 204).
     */
    @Transactional
    public void logout(Long currentUserId, String refreshToken) {
        TokenService.RefreshToken parsed;
        try {
            parsed = tokenService.parseRefresh(refreshToken);
        } catch (AuthTokenException e) {
            // Толерантный logout: невалидный/просроченный токен → просто 204.
            log.debug("Logout with non-parseable refresh token for userId={}", currentUserId);
            return;
        }

        if (!parsed.userId().equals(currentUserId)) {
            log.warn("Logout refresh token userId mismatch: tokenUserId={}, currentUserId={}",
                    parsed.userId(), currentUserId);
            return;
        }

        // rowcount игнорируем: повторный logout того же токена → всё ещё 204.
        revokedRepository.revokeIfAbsent(parsed.jti(), parsed.expiresAt());
        log.info("Logout: revoked refresh token for userId={}", currentUserId);
    }

    /**
     * Инвалидирует все refresh-токены пользователя через эпоху
     * {@code tokens_valid_from = now}.
     */
    @Transactional
    public void logoutAll(Long currentUserId) {
        userRepository.updateTokensValidFrom(currentUserId, clock.instant().truncatedTo(ChronoUnit.SECONDS));
        log.info("Logout-all: bumped token epoch for userId={}", currentUserId);
    }
}

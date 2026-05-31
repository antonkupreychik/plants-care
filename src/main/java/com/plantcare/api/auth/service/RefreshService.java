package com.plantcare.api.auth.service;

import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ротация пары токенов по refresh-токену (issue #88, ADR-011).
 *
 * <p>Шаги в одной транзакции: проверить токен → атомарно отозвать старый jti
 * (replay-защита) → выдать новую пару. Внешних HTTP-вызовов нет, всё локально.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshService {

    private final TokenService tokenService;
    private final RefreshTokenBlacklistService blacklistService;
    private final UserRepository userRepository;

    @Transactional
    public TokenPair rotate(String refreshToken) {
        TokenService.RefreshToken parsed = tokenService.parseRefresh(refreshToken);

        User user = userRepository.findById(parsed.userId())
                .orElseThrow(() -> AuthTokenException.invalid("User from refresh token not found"));

        // Эпоха logout-all (issue #178): токен, выданный до tokens_valid_from,
        // невалиден. NULL → проверка пропускается (backward-compat).
        var tokensValidFrom = user.getTokensValidFrom();
        if (tokensValidFrom != null && parsed.issuedAt().getEpochSecond() < tokensValidFrom.getEpochSecond()) {
            log.warn("Refresh token issued before logout-all epoch for userId={}", user.getId());
            throw AuthTokenException.revoked("Token issued before logout-all");
        }

        // Атомарно отзываем предъявленный jti. 0 вставленных строк → уже отозван.
        blacklistService.revokeOrThrow(parsed.jti(), parsed.expiresAt());

        log.info("Rotated token pair for userId={}", user.getId());
        return tokenService.issuePair(user);
    }
}

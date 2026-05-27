package com.plantcare.api.auth.service;

import com.plantcare.api.config.AuthProperties;
import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.core.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * Выдача и разбор НАШИХ токенов (issue #88, ADR-011). Access и refresh — JWT,
 * подписанные нашим HS256-секретом. Claim {@code sub} = {@code users.id},
 * {@code typ} = {@code access|refresh}, refresh дополнительно несёт {@code jti}.
 *
 * <p>Текущее время — только через инжектируемый {@link Clock}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    static final String CLAIM_TYPE = "typ";
    static final String TYPE_ACCESS = "access";
    static final String TYPE_REFRESH = "refresh";

    private final JwtEncoder appJwtEncoder;
    /**
     * Декодер для refresh: проверяет подпись/exp/iss, но НЕ требует
     * {@code typ=access} (в отличие от resource-server-декодера {@code appJwtDecoder}).
     * Имя поля = имя бина — Spring резолвит по имени.
     */
    private final JwtDecoder appRefreshJwtDecoder;
    private final AuthProperties authProperties;
    private final Clock clock;

    /** Выдаёт новую пару access+refresh для пользователя. */
    public TokenPair issuePair(User user) {
        Instant now = clock.instant();
        AuthProperties.Jwt cfg = authProperties.getJwt();

        long accessTtlSeconds = cfg.getAccessTtl().toSeconds();
        String access = encode(user.getId(), TYPE_ACCESS, now, cfg.getAccessTtl().toSeconds(), null);

        UUID refreshJti = UUID.randomUUID();
        String refresh = encode(user.getId(), TYPE_REFRESH, now, cfg.getRefreshTtl().toSeconds(), refreshJti);

        return new TokenPair(access, refresh, accessTtlSeconds);
    }

    /**
     * Разбирает и валидирует refresh-токен: подпись, срок, {@code typ=refresh},
     * наличие {@code jti}. Бросает {@link AuthTokenException} с подходящим кодом.
     */
    public RefreshToken parseRefresh(String token) {
        Jwt jwt = decode(token);

        if (!TYPE_REFRESH.equals(jwt.getClaimAsString(CLAIM_TYPE))) {
            throw AuthTokenException.invalid("Not a refresh token");
        }
        String jtiClaim = jwt.getId();
        if (jtiClaim == null) {
            throw AuthTokenException.invalid("Refresh token has no jti");
        }
        UUID jti;
        try {
            jti = UUID.fromString(jtiClaim);
        } catch (IllegalArgumentException e) {
            throw AuthTokenException.invalid("Refresh token jti is malformed", e);
        }
        Long userId = parseUserId(jwt.getSubject());
        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null) {
            throw AuthTokenException.invalid("Refresh token has no exp");
        }
        return new RefreshToken(userId, jti, expiresAt);
    }

    private Jwt decode(String token) {
        try {
            return appRefreshJwtDecoder.decode(token);
        } catch (JwtValidationException e) {
            // Просрочку определяем по коду ошибки валидатора (а не по тексту
            // сообщения, который меняется между версиями Spring Security).
            if (isExpired(e.getErrors())) {
                throw AuthTokenException.expired("Token expired");
            }
            throw AuthTokenException.invalid("Token is invalid", e);
        } catch (JwtException e) {
            throw AuthTokenException.invalid("Token is invalid", e);
        }
    }

    /**
     * Считает токен просроченным, если среди ошибок валидации (коллекция
     * {@link OAuth2Error}) есть относящаяся к {@code exp}. Разбираем структуру
     * ошибок, а не текст исключения, — устойчиво к смене формулировок при
     * апгрейде Spring Security.
     */
    private boolean isExpired(Collection<OAuth2Error> errors) {
        return errors.stream().anyMatch(err -> {
            String description = err.getDescription();
            return description != null
                    && description.toLowerCase().contains(JwtClaimNames.EXP);
        });
    }

    private Long parseUserId(String sub) {
        if (sub == null) {
            throw AuthTokenException.invalid("Token has no subject");
        }
        try {
            return Long.parseLong(sub);
        } catch (NumberFormatException e) {
            throw AuthTokenException.invalid("Token subject is not a user id", e);
        }
    }

    private String encode(Long userId, String type, Instant issuedAt, long ttlSeconds, UUID jti) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(authProperties.getJwt().getIssuer())
                .subject(String.valueOf(userId))
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(ttlSeconds))
                .claim(CLAIM_TYPE, type);
        if (jti != null) {
            claims.id(jti.toString());
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return appJwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    /** Разобранный refresh-токен. */
    public record RefreshToken(Long userId, UUID jti, Instant expiresAt) {
    }
}

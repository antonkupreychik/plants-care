package com.plantcare.api.v1;

import com.plantcare.api.auth.exception.RateLimitExceededException;
import com.plantcare.api.auth.ratelimit.TelegramAuthRateLimiter;
import com.plantcare.api.auth.service.TelegramAuthService;
import com.plantcare.api.auth.service.TokenPair;
import com.plantcare.api.generated.model.TokenPairResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issue #318: вход существующих Telegram-юзеров через бот-код.
 *
 * <p>Простой {@code @RestController} (НЕ {@code implements *Api}): эндпоинты пока
 * вне OpenAPI-спеки — её обновит отдельный шаг синхронизации контракта после
 * мержа. Request/response — ручные DTO, кроме ответа verify, который переиспользует
 * сгенерированную {@link TokenPairResponse} (контракт: «не плодить новый DTO»).
 *
 * <p>Путь под {@code /api/v1/auth/**} → permitAll в {@code ApiSecurityConfig}
 * (вход анонимный, токена ещё нет).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth/telegram")
@RequiredArgsConstructor
public class TelegramAuthController {

    private final TelegramAuthService telegramAuthService;
    private final TelegramAuthRateLimiter rateLimiter;
    private final HttpServletRequest httpRequest;

    /** Тело {@code POST /start}. {@code deviceId} опционален и пока игнорируется. */
    public record TelegramStartRequest(String deviceId) {
    }

    /** Ответ {@code POST /start}. */
    public record TelegramStartResponse(
            String sessionId, String deepLink, int codeLength, long resendAfterSec) {
    }

    /** Тело {@code POST /verify}. chat_id НИКОГДА не принимается от клиента. */
    public record TelegramVerifyRequest(
            @NotBlank String sessionId,
            @NotBlank String code) {
    }

    @PostMapping("/start")
    public TelegramStartResponse start(@RequestBody(required = false) TelegramStartRequest request) {
        enforceRateLimit();
        var result = telegramAuthService.start();
        return new TelegramStartResponse(
                result.sessionId(), result.deepLink(), result.codeLength(), result.resendAfterSec());
    }

    @PostMapping("/verify")
    public TokenPairResponse verify(@Valid @RequestBody TelegramVerifyRequest request) {
        enforceRateLimit();
        TokenPair pair = telegramAuthService.verify(request.sessionId(), request.code());
        return new TokenPairResponse(
                pair.accessToken(), pair.refreshToken(), pair.expiresIn(), TokenPair.TOKEN_TYPE);
    }

    private void enforceRateLimit() {
        String key = "ip:" + httpRequest.getRemoteAddr();
        if (rateLimiter.isBlocked(key)) {
            log.warn("Telegram auth rate-limit triggered");
            throw new RateLimitExceededException(
                    "Too many Telegram auth requests", rateLimiter.getWindowSeconds());
        }
        rateLimiter.record(key);
    }
}

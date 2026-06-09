package com.plantcare.api.auth.service;

import com.plantcare.api.auth.exception.TelegramAuthException;
import com.plantcare.api.config.TelegramAuthProperties;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Optional;

/**
 * Issue #318: вход существующих Telegram-юзеров через бот-код.
 *
 * <p>Флоу из трёх шагов:
 * <ol>
 *   <li>{@link #start()} — клиент создаёт анонимную сессию входа (без chat_id/кода),
 *       получает {@code sessionId} для deep link.</li>
 *   <li>{@link #bindCode(String, Long)} — бот, получив {@code /start auth_<sessionId>},
 *       привязывает к сессии {@code chat_id} ИЗ Telegram-update и сгенерированный
 *       6-значный код; возвращает код для отправки в чат.</li>
 *   <li>{@link #verify(String, String)} — клиент подтверждает код; сервис резолвит
 *       существующего {@link User} по {@code telegram_chat_id} и выдаёт JWT-пару.
 *       Нового пользователя НЕ создаёт.</li>
 * </ol>
 *
 * <p>Безопасность: {@code chat_id} приходит только из {@link #bindCode} (вызывается
 * ботом из update), верификация резолвит юзера именно по нему — клиент не может
 * подставить чужой chat_id.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramAuthService {

    private final TelegramAuthSessionStore sessionStore;
    private final TelegramAuthProperties properties;
    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final SecureRandom secureRandom;

    /** Результат старта сессии входа. */
    public record StartResult(String sessionId, String deepLink, int codeLength, long resendAfterSec) {
    }

    /**
     * Создаёт анонимную сессию входа и возвращает данные для deep link.
     * Сам код ещё не сгенерирован — его выдаст бот при переходе по ссылке.
     */
    public StartResult start() {
        String sessionId = newSessionId();
        sessionStore.create(sessionId);
        String deepLink = "t.me/" + properties.botUsername() + "?start=auth_" + sessionId;
        log.info("Telegram auth session started: sessionId={}", sessionId);
        return new StartResult(sessionId, deepLink, properties.codeLength(), properties.resendAfterSec());
    }

    /**
     * Бот-хендлер: привязывает chat_id (из Telegram-update) и новый код к сессии.
     * Возвращает {@code Optional.empty()}, если сессия истекла/не найдена (бот тогда
     * не шлёт код). Код генерируется заново на каждый переход по ссылке (resend).
     *
     * @param sessionId      идентификатор из payload {@code auth_<sessionId>}
     * @param telegramChatId chat_id ИЗ Telegram-update (не из клиентского тела!)
     * @return сгенерированный код для отправки в чат, либо empty если сессии нет
     */
    public Optional<String> bindCode(String sessionId, Long telegramChatId) {
        Optional<TelegramAuthSession> existing = sessionStore.find(sessionId);
        if (existing.isEmpty()) {
            log.info("Telegram auth bindCode: session not found/expired, sessionId={}", sessionId);
            return Optional.empty();
        }

        String code = generateCode();
        sessionStore.save(existing.get().withBoundCode(telegramChatId, code));
        log.info("Telegram auth code bound to session: sessionId={}, chatId={}", sessionId, telegramChatId);
        return Optional.of(code);
    }

    /**
     * Подтверждает код, резолвит существующего юзера по {@code telegram_chat_id}
     * сессии и выдаёт JWT-пару. Нового пользователя НЕ создаёт.
     *
     * @throws TelegramAuthException с кодом {@code session_expired} / {@code invalid_code}
     *         / {@code too_many_attempts} / {@code telegram_user_not_found}
     */
    public TokenPair verify(String sessionId, String code) {
        TelegramAuthSession session = sessionStore.find(sessionId)
                .orElseThrow(TelegramAuthException::sessionExpired);

        // Код ещё не введён неверно столько раз — но если уже на пределе, гасим.
        if (session.attempts() >= properties.maxAttempts()) {
            sessionStore.delete(sessionId);
            throw TelegramAuthException.tooManyAttempts();
        }

        // Сессия без привязанного кода (бот ещё не сходил по ссылке) или несовпадение —
        // это неверный код: инкрементируем попытку, при достижении лимита гасим сессию.
        if (session.isUnbound() || !constantTimeEquals(session.code(), code)) {
            TelegramAuthSession updated = session.withIncrementedAttempts();
            if (updated.attempts() >= properties.maxAttempts()) {
                sessionStore.delete(sessionId);
                log.info("Telegram auth: too many attempts, session burned sessionId={}", sessionId);
                throw TelegramAuthException.tooManyAttempts();
            }
            sessionStore.save(updated);
            throw TelegramAuthException.invalidCode();
        }

        // Код верный — резолвим существующего юзера. Нового НЕ создаём.
        User user = userRepository.findByTelegramChatId(session.telegramChatId())
                .orElseThrow(() -> {
                    // Сессию гасим: код одноразовый, повтор не имеет смысла.
                    sessionStore.delete(sessionId);
                    return TelegramAuthException.userNotFound();
                });

        // Успех — код одноразовый, удаляем сессию.
        sessionStore.delete(sessionId);
        log.info("Telegram login successful: userId={}", user.getId());
        return tokenService.issuePair(user);
    }

    private String newSessionId() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Генерирует numeric-код фиксированной длины (с ведущими нулями). */
    private String generateCode() {
        int length = properties.codeLength();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(secureRandom.nextInt(10));
        }
        return sb.toString();
    }

    /** Сравнение без раннего выхода (не утекает длина совпавшего префикса). */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null || expected.length() != actual.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ actual.charAt(i);
        }
        return diff == 0;
    }
}

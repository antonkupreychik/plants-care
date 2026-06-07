package com.plantcare.api.auth.service;

import com.plantcare.api.auth.exception.GuestConvertException;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Гостевой вход и конвертация гостя в реальный аккаунт (issue #227).
 *
 * <p>Правила гостевого флоу:
 * <ul>
 *   <li>{@code POST /auth/guest} — создаёт нового гостя или восстанавливает сессию
 *       по {@code deviceId}.</li>
 *   <li>{@code POST /auth/guest/convert} — переводит гостевой аккаунт в реальный
 *       (привязывает email/Apple/Google), данные сохраняются.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestAuthService {

    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final MagicLinkService magicLinkService;
    private final AuthService authService;

    /**
     * Результат гостевого входа.
     *
     * @param pair      пара токенов
     * @param isNewUser {@code true} если создан новый пользователь, {@code false} — восстановление
     */
    public record GuestLoginResult(TokenPair pair, boolean isNewUser) {
    }

    /**
     * Гостевой вход по {@code deviceId}. Идемпотентен: повторный вызов с тем же
     * {@code deviceId} восстанавливает сессию, не создаёт дубль.
     *
     * @param deviceId клиентский UUID4 устройства (валидация формата — на уровне контроллера)
     * @return пара токенов + признак нового пользователя
     */
    @Transactional
    public GuestLoginResult loginOrRestore(String deviceId) {
        Optional<User> existing = userRepository.findByDeviceId(deviceId);
        if (existing.isPresent()) {
            User user = existing.get();
            log.info("Guest restore: userId={}, deviceId={}", user.getId(), deviceId);
            return new GuestLoginResult(tokenService.issuePair(user), false);
        }

        User guest = new User();
        guest.setGuest(true);
        guest.setDeviceId(deviceId);
        try {
            User saved = userRepository.saveAndFlush(guest);
            log.info("Guest created: userId={}, deviceId={}", saved.getId(), deviceId);
            return new GuestLoginResult(tokenService.issuePair(saved), true);
        } catch (DataIntegrityViolationException e) {
            // Гонка: параллельный запрос успел создать гостя с тем же deviceId.
            User race = userRepository.findByDeviceId(deviceId)
                    .orElseThrow(() -> e);
            log.info("Guest concurrent create, re-resolved: userId={}", race.getId());
            return new GuestLoginResult(tokenService.issuePair(race), false);
        }
    }

    /**
     * Конвертирует гостевой аккаунт в реальный через email (magic-link flow).
     * После вызова отправляется письмо с magic-link; финальные токены будут выданы
     * при верификации через {@code POST /auth/email/verify}.
     *
     * @param guest  текущий пользователь (должен быть гостем)
     * @param email  email для привязки
     */
    @Transactional
    public void convertViaEmail(User guest, String email) {
        ensureGuest(guest);

        String normalizedEmail = email.trim().toLowerCase();
        checkEmailNotTaken(normalizedEmail, guest.getId());

        // Привязываем email, снимаем признак гостя, чистим deviceId.
        guest.setEmail(normalizedEmail);
        guest.setEmailVerified(false); // подтвердится после клика по magic-link
        guest.setGuest(false);
        guest.setDeviceId(null);
        userRepository.save(guest);

        log.info("Guest converted via EMAIL: userId={}, email={}", guest.getId(), normalizedEmail);
        magicLinkService.request(normalizedEmail);
    }

    /**
     * Конвертирует гостевой аккаунт через Google id-token.
     *
     * @param guest    текущий пользователь (должен быть гостем)
     * @param email    email из верифицированного Google-токена
     * @param subject  Google sub
     * @return новая пара токенов
     */
    @Transactional
    public TokenPair convertViaGoogle(User guest, String email, String subject) {
        ensureGuest(guest);

        String normalizedEmail = email.trim().toLowerCase();
        checkEmailNotTaken(normalizedEmail, guest.getId());
        checkGoogleSubjectNotTaken(subject, guest.getId());

        guest.setEmail(normalizedEmail);
        guest.setEmailVerified(true);
        guest.setGoogleSubject(subject);
        guest.setGuest(false);
        guest.setDeviceId(null);
        userRepository.save(guest);

        log.info("Guest converted via GOOGLE: userId={}", guest.getId());
        return tokenService.issuePair(guest);
    }

    /**
     * Конвертирует гостевой аккаунт через Apple id-token.
     *
     * @param guest    текущий пользователь (должен быть гостем)
     * @param email    email (может быть null при private relay)
     * @param subject  Apple sub
     * @return новая пара токенов
     */
    @Transactional
    public TokenPair convertViaApple(User guest, String email, String subject) {
        ensureGuest(guest);

        if (email != null && !email.isBlank()) {
            String normalizedEmail = email.trim().toLowerCase();
            checkEmailNotTaken(normalizedEmail, guest.getId());
            guest.setEmail(normalizedEmail);
            guest.setEmailVerified(true);
        }
        checkAppleSubjectNotTaken(subject, guest.getId());

        guest.setAppleSubject(subject);
        guest.setGuest(false);
        guest.setDeviceId(null);
        userRepository.save(guest);

        log.info("Guest converted via APPLE: userId={}", guest.getId());
        return tokenService.issuePair(guest);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void ensureGuest(User user) {
        if (!user.isGuest()) {
            throw GuestConvertException.notAGuest(
                    "User is not a guest account and cannot be converted");
        }
    }

    private void checkEmailNotTaken(String normalizedEmail, Long currentUserId) {
        userRepository.findByEmail(normalizedEmail)
                .filter(u -> !u.getId().equals(currentUserId))
                .ifPresent(u -> {
                    throw GuestConvertException.identityAlreadyTaken(
                            "Email is already associated with another account");
                });
    }

    private void checkGoogleSubjectNotTaken(String subject, Long currentUserId) {
        userRepository.findByGoogleSubject(subject)
                .filter(u -> !u.getId().equals(currentUserId))
                .ifPresent(u -> {
                    throw GuestConvertException.identityAlreadyTaken(
                            "Google account is already associated with another account");
                });
    }

    private void checkAppleSubjectNotTaken(String subject, Long currentUserId) {
        userRepository.findByAppleSubject(subject)
                .filter(u -> !u.getId().equals(currentUserId))
                .ifPresent(u -> {
                    throw GuestConvertException.identityAlreadyTaken(
                            "Apple account is already associated with another account");
                });
    }
}

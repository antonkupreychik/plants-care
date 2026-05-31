package com.plantcare.api.auth.service;

import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Линковка/создание пользователя при входе через внешние провайдеры и email
 * (issue #88, ADR-011). Правило: одна почта — один {@link User}.
 *
 * <p>Резолюция идёт <b>от provider subject</b>, затем — от email. Это закрывает
 * два класса багов (issue #88):
 * <ul>
 *   <li>Apple отдаёт email только при ПЕРВОМ входе и не отдаёт при private-relay/
 *       повторных входах — поэтому повторный вход находим по {@code apple_subject},
 *       а не по (отсутствующему) email.</li>
 *   <li>account takeover: непроверенный/чужой email больше не линкуется к
 *       существующему verified-аккаунту — линковка по email только при
 *       {@code emailVerified == true}.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** Провайдер аутентификации для линковки subject'а. */
    public enum Provider {
        APPLE,
        GOOGLE,
        EMAIL
    }

    private final UserRepository userRepository;

    /**
     * Находит существующего пользователя или создаёт нового. Порядок резолюции:
     * <ol>
     *   <li>по provider subject ({@code apple_subject}/{@code google_subject}) —
     *       это тот же юзер, даже если email в этом входе отсутствует;</li>
     *   <li>иначе, если email присутствует И подтверждён провайдером — линковка
     *       к существующему по email или создание нового, с привязкой subject;</li>
     *   <li>иначе (subject не найден, email отсутствует/не подтверждён) — это
     *       первый вход без пригодного идентификатора → {@link AuthTokenException}
     *       с кодом {@code EMAIL_REQUIRED}.</li>
     * </ol>
     *
     * @param email           email из токена (может быть {@code null} для Apple)
     * @param emailVerified   подтвердил ли провайдер этот email
     * @param provider        провайдер входа
     * @param providerSubject стабильный {@code sub} провайдера; {@code null} для EMAIL
     */
    @Transactional
    public User findOrCreateUser(String email, boolean emailVerified,
                                 Provider provider, String providerSubject) {
        // (1) Резолюция по provider subject — приоритетна. Обрабатывает
        // Apple-повторный-вход с null email и не зависит от верификации email.
        Optional<User> bySubject = findBySubject(provider, providerSubject);
        if (bySubject.isPresent()) {
            User user = bySubject.get();
            applyVerifiedEmail(user, email, emailVerified);
            return user;
        }

        // (2) Линковка/создание по email допустимы только если email подтверждён.
        if (email == null || email.isBlank() || !emailVerified) {
            log.warn("Sign-in via {} without a usable identifier (subject not found, "
                    + "email missing or unverified)", provider);
            throw AuthTokenException.emailRequired(
                    "Provider did not return a verified email and subject is unknown");
        }

        String normalizedEmail = email.trim().toLowerCase();
        return findByEmailOrCreate(normalizedEmail, provider, providerSubject);
    }

    private User findByEmailOrCreate(String normalizedEmail, Provider provider, String providerSubject) {
        Optional<User> byEmail = userRepository.findByEmail(normalizedEmail);
        if (byEmail.isPresent()) {
            User existing = byEmail.get();
            linkSubject(existing, provider, providerSubject);
            existing.setEmailVerified(true);
            return existing;
        }

        User created = new User();
        created.setEmail(normalizedEmail);
        created.setEmailVerified(true);
        linkSubject(created, provider, providerSubject);
        try {
            User saved = userRepository.saveAndFlush(created);
            log.info("Created new user via {}", provider);
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Гонка первого входа: параллельный запрос успел вставить этого же
            // юзера и выиграл idx_users_email / idx по subject. Перерезолвим и
            // вернём существующего вместо 500.
            log.info("Concurrent first sign-in for {}, re-resolving existing user", provider);
            User existing = findBySubject(provider, providerSubject)
                    .or(() -> userRepository.findByEmail(normalizedEmail))
                    .orElseThrow(() -> e);
            linkSubject(existing, provider, providerSubject);
            existing.setEmailVerified(true);
            return existing;
        }
    }

    private Optional<User> findBySubject(Provider provider, String providerSubject) {
        if (providerSubject == null) {
            return Optional.empty();
        }
        return switch (provider) {
            case APPLE -> userRepository.findByAppleSubject(providerSubject);
            case GOOGLE -> userRepository.findByGoogleSubject(providerSubject);
            case EMAIL -> Optional.empty();
        };
    }

    /**
     * Проставляет верифицированный email юзеру, найденному по subject, не затирая
     * уже подтверждённый email при повторном входе. {@code emailVerified} в
     * {@code false} никогда не понижает существующий статус.
     */
    private void applyVerifiedEmail(User user, String email, boolean emailVerified) {
        if (email == null || email.isBlank() || !emailVerified) {
            return;
        }
        String normalizedEmail = email.trim().toLowerCase();
        if (user.getEmail() == null) {
            user.setEmail(normalizedEmail);
        }
        if (normalizedEmail.equals(user.getEmail())) {
            user.setEmailVerified(true);
        }
    }

    private void linkSubject(User user, Provider provider, String providerSubject) {
        if (providerSubject == null) {
            return;
        }
        switch (provider) {
            case APPLE -> {
                if (user.getAppleSubject() == null) {
                    user.setAppleSubject(providerSubject);
                }
            }
            case GOOGLE -> {
                if (user.getGoogleSubject() == null) {
                    user.setGoogleSubject(providerSubject);
                }
            }
            case EMAIL -> {
                // email-вход не несёт provider subject — линковать нечего
            }
        }
    }
}

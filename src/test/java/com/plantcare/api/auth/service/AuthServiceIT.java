package com.plantcare.api.auth.service;

import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционный тест линковки/создания пользователя (issue #88, ADR-011).
 *
 * <p>Правило: один email — один {@link User}. Реальный Postgres.
 */
class AuthServiceIT extends IntegrationTestBase {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void cleanup() {
        userRepository.deleteAll();
    }

    // ===================== новый пользователь =====================

    @Test
    void should_create_user_without_telegram_when_email_is_new() {
        // act
        User created = authService.findOrCreateUser(
                "newbie@example.com", true, AuthService.Provider.GOOGLE, "google-sub-1");

        // assert
        assertThat(created.getId()).isNotNull();
        assertThat(created.getEmail()).isEqualTo("newbie@example.com");
        assertThat(created.isEmailVerified()).isTrue();
        assertThat(created.getTelegramChatId()).isNull();
        assertThat(created.getGoogleSubject()).isEqualTo("google-sub-1");

        assertThat(userRepository.findByEmail("newbie@example.com")).isPresent();
    }

    @Test
    void should_normalize_email_to_lowercase_when_creating() {
        // act
        User created = authService.findOrCreateUser(
                "  MiXeD.Case@Example.COM ", true, AuthService.Provider.EMAIL, null);

        // assert
        assertThat(created.getEmail()).isEqualTo("mixed.case@example.com");
    }

    // ===================== линковка существующего =====================

    @Test
    void should_return_same_user_when_email_already_exists() {
        // arrange — пользователь уже есть (например, ранее вошёл через email)
        User existing = userRepository.save(User.builder()
                .email("linkme@example.com")
                .emailVerified(false)
                .build());

        // act — второй вход с тем же email через Apple
        User linked = authService.findOrCreateUser(
                "linkme@example.com", true, AuthService.Provider.APPLE, "apple-sub-9");

        // assert — тот же пользователь (не дубль), subject привязан, email подтверждён
        assertThat(linked.getId()).isEqualTo(existing.getId());
        assertThat(linked.getAppleSubject()).isEqualTo("apple-sub-9");
        assertThat(linked.isEmailVerified()).isTrue();
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void should_not_overwrite_existing_provider_subject_when_linking_again() {
        // arrange — у пользователя уже привязан Google sub
        userRepository.save(User.builder()
                .email("stable@example.com")
                .emailVerified(true)
                .googleSubject("original-google-sub")
                .build());

        // act — повторная линковка Google с другим subject
        User linked = authService.findOrCreateUser(
                "stable@example.com", true, AuthService.Provider.GOOGLE, "different-google-sub");

        // assert — оригинальный subject сохранён (линкуем только если был null)
        assertThat(linked.getGoogleSubject()).isEqualTo("original-google-sub");
    }

    // ===================== Apple: резолв по subject (issue #88, BLOCKING-#2) =====================

    @Test
    void should_return_same_user_by_subject_when_apple_relogin_has_null_email() {
        // arrange — первый вход Apple отдаёт email+verified, создаётся юзер с apple_subject
        User first = authService.findOrCreateUser(
                "applefan@example.com", true, AuthService.Provider.APPLE, "apple-sub-relogin");
        Long firstId = first.getId();

        // act — повторный вход тем же subject, но Apple НЕ отдал email (private relay)
        User second = authService.findOrCreateUser(
                null, false, AuthService.Provider.APPLE, "apple-sub-relogin");

        // assert — тот же юзер по subject, без дубля, email не затёрт в null
        assertThat(second.getId()).isEqualTo(firstId);
        assertThat(second.getEmail()).isEqualTo("applefan@example.com");
        assertThat(second.isEmailVerified()).isTrue();
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void should_throw_email_required_when_apple_email_unverified_and_subject_unknown() {
        // arrange — существует verified-юзер с этим email (через другой провайдер)
        User victim = userRepository.save(User.builder()
                .email("target@example.com")
                .emailVerified(true)
                .googleSubject("google-owner")
                .build());

        // act + assert — Apple: email есть, но unverified, subject новый → EMAIL_REQUIRED
        assertThatThrownBy(() -> authService.findOrCreateUser(
                "target@example.com", false, AuthService.Provider.APPLE, "new-apple-sub"))
                .isInstanceOf(AuthTokenException.class)
                .satisfies(e -> assertThat(((AuthTokenException) e).getCode())
                        .isEqualTo(AuthTokenException.Code.EMAIL_REQUIRED));

        // assert — НЕ создан дубль и существующий verified-юзер НЕ перелинкован на apple_subject (no takeover)
        User reloaded = userRepository.findById(victim.getId()).orElseThrow();
        assertThat(reloaded.getAppleSubject()).isNull();
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void should_throw_email_required_when_apple_has_no_email_and_subject_unknown() {
        // act + assert — первый вход Apple без email и с неизвестным subject → EMAIL_REQUIRED
        assertThatThrownBy(() -> authService.findOrCreateUser(
                null, false, AuthService.Provider.APPLE, "totally-new-sub"))
                .isInstanceOf(AuthTokenException.class)
                .satisfies(e -> assertThat(((AuthTokenException) e).getCode())
                        .isEqualTo(AuthTokenException.Code.EMAIL_REQUIRED));

        assertThat(userRepository.count()).isZero();
    }

    @Test
    void should_not_downgrade_email_verified_to_false_on_relogin() {
        // arrange — юзер с подтверждённым email, привязан apple_subject
        User existing = userRepository.save(User.builder()
                .email("verified@example.com")
                .emailVerified(true)
                .appleSubject("apple-sub-verified")
                .build());

        // act — повторный вход по subject с emailVerified=false (Apple так может)
        User result = authService.findOrCreateUser(
                "verified@example.com", false, AuthService.Provider.APPLE, "apple-sub-verified");

        // assert — статус НЕ понижен до false
        assertThat(result.getId()).isEqualTo(existing.getId());
        assertThat(result.isEmailVerified()).isTrue();
    }
}

package com.plantcare.api.auth.service;

import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.core.domain.MagicLinkToken;
import com.plantcare.core.repository.MagicLinkTokenRepository;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * Интеграционный тест magic-link входа (issue #88, ADR-011).
 *
 * <p>Реальный Postgres. Внешний мир — {@link JavaMailSender} — замокан (это
 * единственная внешняя зависимость; БД не мокаем по правилам проекта).
 *
 * <p>{@code management.health.mail.enabled=false}: при замоканном JavaMailSender
 * mail-health-contributor падает на старте контекста ({@code 'beans' must not be empty}),
 * а сам health-чек к смыслу теста отношения не имеет.
 */
@TestPropertySource(properties = "management.health.mail.enabled=false")
class MagicLinkServiceIT extends IntegrationTestBase {

    @Autowired
    private MagicLinkService magicLinkService;

    @Autowired
    private MagicLinkTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private Clock clock;

    @MockitoBean
    private JavaMailSender mailSender;

    @AfterEach
    void cleanup() {
        tokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    private static String sha256Hex(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    // ===================== request =====================

    @Test
    void should_store_token_hash_not_plaintext_and_send_email_when_requested() {
        // arrange
        String email = "Magic.User@Example.com";

        // act
        magicLinkService.request(email);

        // assert — письмо отправлено на нормализованный адрес
        verify(mailSender).send(any(SimpleMailMessage.class));

        // assert — в БД ровно одна запись, email нормализован, хранится хэш (64 hex), не сырой токен
        var tokens = tokenRepository.findAll();
        assertThat(tokens).hasSize(1);
        MagicLinkToken stored = tokens.get(0);
        assertThat(stored.getEmail()).isEqualTo("magic.user@example.com");
        assertThat(stored.getTokenHash()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(stored.getConsumedAt()).isNull();
        assertThat(stored.getExpiresAt()).isAfter(clock.instant());
    }

    // ===================== verify happy =====================

    @Test
    void should_issue_token_pair_and_create_user_when_verifying_valid_magic_link() throws Exception {
        // arrange — кладём токен напрямую, чтобы знать сырое значение (request его не возвращает)
        String rawToken = "raw-magic-token-happy";
        tokenRepository.save(new MagicLinkToken(
                "newuser@example.com", sha256Hex(rawToken), clock.instant().plusSeconds(600)));

        // act
        TokenPair pair = magicLinkService.verify(rawToken);

        // assert — выдана пара
        assertThat(pair.accessToken()).isNotBlank();
        assertThat(pair.refreshToken()).isNotBlank();

        // assert — пользователь создан (без Telegram), токен погашен
        var user = userRepository.findByEmail("newuser@example.com");
        assertThat(user).isPresent();
        assertThat(user.get().getTelegramChatId()).isNull();
        assertThat(tokenRepository.findByTokenHash(sha256Hex(rawToken)))
                .get()
                .satisfies(t -> assertThat(t.getConsumedAt()).isNotNull());
    }

    // ===================== one-time / replay =====================

    @Test
    void should_reject_second_verify_when_token_already_consumed() throws Exception {
        // arrange
        String rawToken = "raw-magic-token-replay";
        tokenRepository.save(new MagicLinkToken(
                "replay@example.com", sha256Hex(rawToken), clock.instant().plusSeconds(600)));
        magicLinkService.verify(rawToken);

        // act + assert — повторный verify тем же токеном → invalid (one-time)
        assertThatThrownBy(() -> magicLinkService.verify(rawToken))
                .isInstanceOf(AuthTokenException.class)
                .satisfies(e -> assertThat(((AuthTokenException) e).getCode())
                        .isEqualTo(AuthTokenException.Code.TOKEN_INVALID));
    }

    // ===================== expired =====================

    @Test
    void should_reject_verify_when_token_expired() throws Exception {
        // arrange — токен с expires_at в прошлом
        String rawToken = "raw-magic-token-expired";
        tokenRepository.save(new MagicLinkToken(
                "expired@example.com", sha256Hex(rawToken), clock.instant().minusSeconds(60)));

        // act + assert
        assertThatThrownBy(() -> magicLinkService.verify(rawToken))
                .isInstanceOf(AuthTokenException.class)
                .satisfies(e -> assertThat(((AuthTokenException) e).getCode())
                        .isEqualTo(AuthTokenException.Code.TOKEN_INVALID));

        // assert — истёкший токен НЕ погашен (consumed_at остался null)
        var token = tokenRepository.findByTokenHash(sha256Hex(rawToken));
        assertThat(token).get().satisfies(t -> assertThat(t.getConsumedAt()).isNull());
    }

    @Test
    void should_reject_verify_when_token_unknown() {
        // act + assert — неизвестный токен
        assertThatThrownBy(() -> magicLinkService.verify("never-issued-token"))
                .isInstanceOf(AuthTokenException.class);
    }

    // ===================== email вне транзакции: запись сохраняется даже если SMTP падает =====================

    @Test
    void should_persist_token_even_when_mail_sending_fails() {
        // arrange — MailSender кидает (как реальный SMTP-сбой). MagicLinkMailSender
        // глушит исключение, а запись токена коммитится до отправки письма.
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        // act — наружу исключение не пробрасывается (anti-enumeration)
        magicLinkService.request("smtp-fail@example.com");

        // assert — запись всё равно сохранена (письмо шлётся ВНЕ транзакции БД)
        assertThat(tokenRepository.findAll())
                .anySatisfy(t -> assertThat(t.getEmail()).isEqualTo("smtp-fail@example.com"));
        verify(mailSender).send(any(SimpleMailMessage.class));
    }
}

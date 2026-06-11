package com.plantcare.api.auth.service;

import com.plantcare.api.auth.exception.TelegramAuthException;
import com.plantcare.api.config.TelegramAuthProperties;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link TelegramAuthService} (issue #318). Хранилище сессий и
 * репозиторий замоканы — проверяем ветвление verify/start/bindCode без Redis.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TelegramAuthService — вход существующих Telegram-юзеров")
class TelegramAuthServiceTest {

    private static final String SESSION_ID = "sess-1";
    private static final Long CHAT_ID = 777L;
    private static final String CODE = "123456";

    @Mock
    private TelegramAuthSessionStore sessionStore;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TokenService tokenService;

    private TelegramAuthService service;

    private final TelegramAuthProperties properties =
            new TelegramAuthProperties("plantcarebot", 6, Duration.ofMinutes(5), 60, 5);

    @BeforeEach
    void setUp() {
        service = new TelegramAuthService(
                sessionStore, properties, userRepository, tokenService, new SecureRandom());
    }

    private TelegramAuthSession bound(int attempts) {
        return new TelegramAuthSession(SESSION_ID, CHAT_ID, CODE, attempts,
                Instant.now().plus(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("start создаёт сессию и возвращает deepLink на бота с auth_<sessionId>")
    void should_create_session_and_build_deep_link_on_start() {
        when(sessionStore.create(any())).thenAnswer(inv ->
                new TelegramAuthSession(inv.getArgument(0), null, null, 0, Instant.now()));

        var result = service.start();

        assertThat(result.codeLength()).isEqualTo(6);
        assertThat(result.resendAfterSec()).isEqualTo(60);
        assertThat(result.sessionId()).isNotBlank();
        assertThat(result.deepLink())
                .isEqualTo("https://t.me/plantcarebot?start=auth_" + result.sessionId());
    }

    @Test
    @DisplayName("bindCode на существующей сессии генерит код фикс. длины и сохраняет сессию")
    void should_bind_code_when_session_exists() {
        when(sessionStore.find(SESSION_ID)).thenReturn(Optional.of(
                new TelegramAuthSession(SESSION_ID, null, null, 0, Instant.now())));

        Optional<String> code = service.bindCode(SESSION_ID, CHAT_ID);

        assertThat(code).isPresent();
        assertThat(code.get()).hasSize(6).containsOnlyDigits();
        ArgumentCaptor<TelegramAuthSession> captor = ArgumentCaptor.forClass(TelegramAuthSession.class);
        verify(sessionStore).save(captor.capture());
        assertThat(captor.getValue().telegramChatId()).isEqualTo(CHAT_ID);
        assertThat(captor.getValue().code()).isEqualTo(code.get());
    }

    @Test
    @DisplayName("bindCode на истёкшей/несуществующей сессии возвращает empty, ничего не сохраняет")
    void should_return_empty_when_session_missing_on_bind() {
        when(sessionStore.find(SESSION_ID)).thenReturn(Optional.empty());

        assertThat(service.bindCode(SESSION_ID, CHAT_ID)).isEmpty();

        verify(sessionStore, never()).save(any());
    }

    @Test
    @DisplayName("verify с верным кодом резолвит существующего юзера и выдаёт пару токенов")
    void should_issue_tokens_when_code_valid_and_user_exists() {
        User user = User.builder().telegramChatId(CHAT_ID).build();
        TokenPair pair = new TokenPair("acc", "ref", 3600);
        when(sessionStore.find(SESSION_ID)).thenReturn(Optional.of(bound(0)));
        when(userRepository.findByTelegramChatId(CHAT_ID)).thenReturn(Optional.of(user));
        when(tokenService.issuePair(user)).thenReturn(pair);

        TokenPair result = service.verify(SESSION_ID, CODE);

        assertThat(result).isEqualTo(pair);
        // одноразовость: сессия удалена после успеха
        verify(sessionStore).delete(SESSION_ID);
    }

    @Test
    @DisplayName("verify НЕ создаёт нового юзера — только резолвит существующего")
    void should_not_create_new_user_on_verify() {
        when(sessionStore.find(SESSION_ID)).thenReturn(Optional.of(bound(0)));
        when(userRepository.findByTelegramChatId(CHAT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(SESSION_ID, CODE))
                .isInstanceOf(TelegramAuthException.class)
                .extracting(e -> ((TelegramAuthException) e).getCode())
                .isEqualTo(TelegramAuthException.Code.TELEGRAM_USER_NOT_FOUND);

        verify(userRepository, never()).save(any());
        verify(tokenService, never()).issuePair(any());
    }

    @Test
    @DisplayName("verify с истёкшей/несуществующей сессией → session_expired")
    void should_throw_session_expired_when_session_missing() {
        when(sessionStore.find(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(SESSION_ID, CODE))
                .isInstanceOf(TelegramAuthException.class)
                .extracting(e -> ((TelegramAuthException) e).getCode())
                .isEqualTo(TelegramAuthException.Code.SESSION_EXPIRED);
    }

    @Test
    @DisplayName("verify с неверным кодом инкрементирует попытки и бросает invalid_code")
    void should_increment_attempts_and_throw_invalid_code() {
        when(sessionStore.find(SESSION_ID)).thenReturn(Optional.of(bound(0)));

        assertThatThrownBy(() -> service.verify(SESSION_ID, "000000"))
                .isInstanceOf(TelegramAuthException.class)
                .extracting(e -> ((TelegramAuthException) e).getCode())
                .isEqualTo(TelegramAuthException.Code.INVALID_CODE);

        ArgumentCaptor<TelegramAuthSession> captor = ArgumentCaptor.forClass(TelegramAuthSession.class);
        verify(sessionStore).save(captor.capture());
        assertThat(captor.getValue().attempts()).isEqualTo(1);
        verify(sessionStore, never()).delete(any());
    }

    @Test
    @DisplayName("verify на последней попытке гасит сессию и бросает too_many_attempts")
    void should_burn_session_and_throw_too_many_attempts_on_last_attempt() {
        // attempts=4, лимит 5: этот неверный ввод доводит до 5 → гасим
        when(sessionStore.find(SESSION_ID)).thenReturn(Optional.of(bound(4)));

        assertThatThrownBy(() -> service.verify(SESSION_ID, "000000"))
                .isInstanceOf(TelegramAuthException.class)
                .extracting(e -> ((TelegramAuthException) e).getCode())
                .isEqualTo(TelegramAuthException.Code.TOO_MANY_ATTEMPTS);

        verify(sessionStore).delete(SESSION_ID);
    }

    @Test
    @DisplayName("verify при уже исчерпанных попытках сразу гасит и бросает too_many_attempts")
    void should_throw_too_many_attempts_when_already_at_limit() {
        when(sessionStore.find(SESSION_ID)).thenReturn(Optional.of(bound(5)));

        assertThatThrownBy(() -> service.verify(SESSION_ID, CODE))
                .isInstanceOf(TelegramAuthException.class)
                .extracting(e -> ((TelegramAuthException) e).getCode())
                .isEqualTo(TelegramAuthException.Code.TOO_MANY_ATTEMPTS);

        verify(sessionStore).delete(SESSION_ID);
        verify(userRepository, never()).findByTelegramChatId(anyLong());
    }
}

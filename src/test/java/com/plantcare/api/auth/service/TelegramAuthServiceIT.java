package com.plantcare.api.auth.service;

import com.plantcare.api.auth.exception.TelegramAuthException;
import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционный тест входа существующих Telegram-юзеров (issue #318).
 *
 * <p>Реальные Postgres + Redis (Testcontainers). Сессии лежат в Redis с TTL,
 * юзеры — в Postgres. Внешних API нет (бот не вызывается — его роль играет прямой
 * {@code bindCode}). Покрывает: успешный вход существующего юзера, отсутствие
 * создания нового, не найден, истёкшая сессия, превышение попыток.
 */
@TestPropertySource(properties = {
        "management.health.mail.enabled=false",
        "plantcare.auth.telegram.bot-username=plantcaretestbot",
        "plantcare.auth.telegram.max-attempts=3",
        // короткий TTL для проверки истечения без долгого ожидания
        "plantcare.auth.telegram.session-ttl=PT1S"
})
class TelegramAuthServiceIT extends IntegrationTestBase {

    @Autowired
    private TelegramAuthService telegramAuthService;

    @Autowired
    private TelegramAuthSessionStore sessionStore;

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void cleanup() {
        userRepository.deleteAll();
    }

    private User existingTelegramUser(long chatId) {
        User user = User.builder()
                .telegramChatId(chatId)
                .username("tg_user_" + chatId)
                .timezone("Europe/Moscow")
                .build();
        return userRepository.save(user);
    }

    @Test
    @DisplayName("Полный флоу: start → bindCode → verify выдаёт токены существующему юзеру")
    void should_login_existing_user_full_flow() {
        User user = existingTelegramUser(1001L);

        var start = telegramAuthService.start();
        assertThat(start.deepLink()).isEqualTo("t.me/plantcaretestbot?start=auth_" + start.sessionId());

        Optional<String> code = telegramAuthService.bindCode(start.sessionId(), 1001L);
        assertThat(code).isPresent();

        TokenPair pair = telegramAuthService.verify(start.sessionId(), code.get());

        assertThat(pair.accessToken()).isNotBlank();
        assertThat(pair.refreshToken()).isNotBlank();
        // сессия одноразовая — после успеха исчезла
        assertThat(sessionStore.find(start.sessionId())).isEmpty();
    }

    @Test
    @DisplayName("verify НЕ создаёт нового юзера, когда по chat_id никого нет → telegram_user_not_found")
    void should_not_create_user_when_not_found() {
        long before = userRepository.count();

        var start = telegramAuthService.start();
        String code = telegramAuthService.bindCode(start.sessionId(), 9999L).orElseThrow();

        assertThatThrownBy(() -> telegramAuthService.verify(start.sessionId(), code))
                .isInstanceOf(TelegramAuthException.class)
                .extracting(e -> ((TelegramAuthException) e).getCode())
                .isEqualTo(TelegramAuthException.Code.TELEGRAM_USER_NOT_FOUND);

        assertThat(userRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("verify по истёкшей (TTL) сессии → session_expired")
    void should_expire_session_after_ttl() throws InterruptedException {
        existingTelegramUser(1002L);
        var start = telegramAuthService.start();
        telegramAuthService.bindCode(start.sessionId(), 1002L);

        // session-ttl=PT1S — ждём истечения Redis-ключа
        Thread.sleep(1500);

        assertThatThrownBy(() -> telegramAuthService.verify(start.sessionId(), "123456"))
                .isInstanceOf(TelegramAuthException.class)
                .extracting(e -> ((TelegramAuthException) e).getCode())
                .isEqualTo(TelegramAuthException.Code.SESSION_EXPIRED);
    }

    @Test
    @DisplayName("Неверный код наращивает попытки; на лимите (3) сессия гасится → too_many_attempts")
    void should_burn_session_after_max_attempts() {
        existingTelegramUser(1003L);
        var start = telegramAuthService.start();
        telegramAuthService.bindCode(start.sessionId(), 1003L);

        // attempts: 1, 2 — invalid_code
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> telegramAuthService.verify(start.sessionId(), "000000"))
                    .isInstanceOf(TelegramAuthException.class)
                    .extracting(e -> ((TelegramAuthException) e).getCode())
                    .isEqualTo(TelegramAuthException.Code.INVALID_CODE);
        }

        // 3-я неудача доводит до лимита → too_many_attempts + гашение
        assertThatThrownBy(() -> telegramAuthService.verify(start.sessionId(), "000000"))
                .isInstanceOf(TelegramAuthException.class)
                .extracting(e -> ((TelegramAuthException) e).getCode())
                .isEqualTo(TelegramAuthException.Code.TOO_MANY_ATTEMPTS);

        assertThat(sessionStore.find(start.sessionId())).isEmpty();
    }
}

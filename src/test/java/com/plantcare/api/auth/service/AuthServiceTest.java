package com.plantcare.api.auth.service;

import com.plantcare.core.domain.User;
import com.plantcare.core.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link AuthService} для гонки первого входа (issue #88, SHOULD-FIX-#3).
 *
 * <p>Гонку (две параллельные транзакции вставляют одного юзера) в Testcontainers
 * воспроизвести трудно/флаково, поэтому моделируем её моком {@link UserRepository}:
 * {@code saveAndFlush} бросает {@link DataIntegrityViolationException} (как при
 * нарушении uniq-индекса), а повторный резолв находит существующего юзера. Метод
 * обязан вернуть его, а не пробросить ошибку (иначе 500 на конкурентном входе).
 *
 * <p>Остальная логика {@link AuthService} покрыта на реальном Postgres в
 * {@code AuthServiceIT}.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AuthService authService;

    @Test
    void should_reresolve_by_subject_when_concurrent_insert_loses_race() {
        // arrange — subject не найден, email не найден → пойдём создавать.
        // saveAndFlush проигрывает гонку (uniq violation). Перерезолв по subject находит победителя.
        User winner = mock(User.class);
        when(userRepository.findByGoogleSubject("race-sub")).thenReturn(
                Optional.empty(),                 // первичный резолв в findOrCreateUser
                Optional.of(winner));             // перерезолв после DataIntegrityViolationException
        when(userRepository.findByEmail("racer@example.com")).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        // act
        User result = authService.findOrCreateUser(
                "racer@example.com", true, AuthService.Provider.GOOGLE, "race-sub");

        // assert — вернули победителя гонки, а не 500
        assertThat(result).isSameAs(winner);
    }

    @Test
    void should_reresolve_by_email_when_concurrent_insert_loses_race_and_subject_absent() {
        // arrange — у победителя гонки нет нашего subject, но email совпадает (вошёл другим способом).
        // EMAIL-провайдер не несёт subject, поэтому findBySubject вернёт empty и резолв пойдёт по email.
        User winner = mock(User.class);
        when(userRepository.findByEmail("emailracer@example.com")).thenReturn(
                Optional.empty(),                 // первичный резолв
                Optional.of(winner));             // перерезолв после конфликта
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        // act
        User result = authService.findOrCreateUser(
                "emailracer@example.com", true, AuthService.Provider.EMAIL, null);

        // assert
        assertThat(result).isSameAs(winner);
    }

    @Test
    void should_rethrow_when_concurrent_violation_but_user_still_not_found() {
        // arrange — конфликт есть, но перерезолв ничего не нашёл (конфликт не из-за нашего юзера).
        // Тогда исходное исключение должно пробрасываться, а не маскироваться.
        when(userRepository.findByGoogleSubject("ghost-sub")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("unrelated constraint"));

        // act + assert
        assertThatThrownBy(() -> authService.findOrCreateUser(
                "ghost@example.com", true, AuthService.Provider.GOOGLE, "ghost-sub"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

package com.plantcare.api.auth.service;

import com.plantcare.api.auth.exception.GuestConvertException;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link GuestAuthService} (issue #227).
 */
@ExtendWith(MockitoExtension.class)
class GuestAuthServiceTest {

    private static final String DEVICE_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Mock
    private UserRepository userRepository;
    @Mock
    private TokenService tokenService;
    @Mock
    private MagicLinkService magicLinkService;
    // AuthService is a dependency of GuestAuthService via constructor injection
    @Mock
    private AuthService authService;

    @InjectMocks
    private GuestAuthService guestAuthService;

    private User guestUser;
    private TokenPair tokenPair;

    @BeforeEach
    void setUp() {
        guestUser = new User();
        guestUser.setGuest(true);
        guestUser.setDeviceId(DEVICE_ID);
        ReflectionTestUtils.setField(guestUser, "id", 1L);

        tokenPair = new TokenPair("access", "refresh", 3600L);
    }

    // ─── loginOrRestore — создание нового гостя ───────────────────────────────

    @Test
    void should_create_new_guest_and_return_is_new_user_true_when_device_id_unknown() {
        // arrange
        when(userRepository.findByDeviceId(DEVICE_ID)).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any())).thenReturn(guestUser);
        when(tokenService.issuePair(guestUser)).thenReturn(tokenPair);

        // act
        var result = guestAuthService.loginOrRestore(DEVICE_ID);

        // assert
        assertThat(result.isNewUser()).isTrue();
        assertThat(result.pair()).isEqualTo(tokenPair);
        verify(userRepository).saveAndFlush(any());
    }

    @Test
    void should_restore_session_and_return_is_new_user_false_when_device_id_exists() {
        // arrange
        when(userRepository.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(guestUser));
        when(tokenService.issuePair(guestUser)).thenReturn(tokenPair);

        // act
        var result = guestAuthService.loginOrRestore(DEVICE_ID);

        // assert
        assertThat(result.isNewUser()).isFalse();
        assertThat(result.pair()).isEqualTo(tokenPair);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void should_resolve_concurrent_guest_creation_when_integrity_violation_occurs() {
        // arrange — race: параллельный запрос уже вставил пользователя
        when(userRepository.findByDeviceId(DEVICE_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(guestUser));
        when(userRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));
        when(tokenService.issuePair(guestUser)).thenReturn(tokenPair);

        // act
        var result = guestAuthService.loginOrRestore(DEVICE_ID);

        // assert — re-resolve, isNewUser=false (уже создан другим запросом)
        assertThat(result.isNewUser()).isFalse();
        verify(userRepository, times(2)).findByDeviceId(DEVICE_ID);
    }

    // ─── convertViaEmail ─────────────────────────────────────────────────────

    @Test
    void should_attach_email_and_send_magic_link_when_converting_guest_via_email() {
        // arrange
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(guestUser)).thenReturn(guestUser);
        doNothing().when(magicLinkService).request("user@example.com");

        // act
        guestAuthService.convertViaEmail(guestUser, "user@example.com");

        // assert — email привязан, isGuest сброшен, deviceId очищен
        assertThat(guestUser.getEmail()).isEqualTo("user@example.com");
        assertThat(guestUser.isGuest()).isFalse();
        assertThat(guestUser.getDeviceId()).isNull();
        verify(magicLinkService).request("user@example.com");
    }

    @Test
    void should_normalise_email_to_lowercase_when_converting_via_email() {
        // arrange
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenReturn(guestUser);

        // act
        guestAuthService.convertViaEmail(guestUser, "  User@Example.COM  ");

        // assert — email нормализован
        assertThat(guestUser.getEmail()).isEqualTo("user@example.com");
    }

    @Test
    void should_throw_not_a_guest_when_converting_real_user() {
        // arrange
        User realUser = new User();
        realUser.setGuest(false);
        realUser.setEmail("existing@example.com");

        // act + assert
        assertThatThrownBy(() -> guestAuthService.convertViaEmail(realUser, "other@example.com"))
                .isInstanceOf(GuestConvertException.class)
                .extracting(e -> ((GuestConvertException) e).getCode())
                .isEqualTo(GuestConvertException.Code.NOT_A_GUEST);
    }

    @Test
    void should_throw_identity_already_taken_when_email_occupied_by_another_user() {
        // arrange — email уже занят другим аккаунтом
        User other = new User();
        ReflectionTestUtils.setField(other, "id", 99L); // different from guestUser.id=1L
        when(userRepository.findByEmail(eq("taken@example.com"))).thenReturn(Optional.of(other));

        // act + assert
        assertThatThrownBy(() -> guestAuthService.convertViaEmail(guestUser, "taken@example.com"))
                .isInstanceOf(GuestConvertException.class)
                .extracting(e -> ((GuestConvertException) e).getCode())
                .isEqualTo(GuestConvertException.Code.IDENTITY_ALREADY_TAKEN);

        verify(magicLinkService, never()).request(any());
    }

    // ─── convertViaGoogle ────────────────────────────────────────────────────

    @Test
    void should_convert_guest_to_google_user_and_return_tokens() {
        // arrange
        when(userRepository.findByEmail("g@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByGoogleSubject("google-sub")).thenReturn(Optional.empty());
        when(userRepository.save(guestUser)).thenReturn(guestUser);
        when(tokenService.issuePair(guestUser)).thenReturn(tokenPair);

        // act
        TokenPair result = guestAuthService.convertViaGoogle(guestUser, "g@example.com", "google-sub");

        // assert
        assertThat(result).isEqualTo(tokenPair);
        assertThat(guestUser.getGoogleSubject()).isEqualTo("google-sub");
        assertThat(guestUser.isGuest()).isFalse();
        assertThat(guestUser.getDeviceId()).isNull();
    }

    @Test
    void should_throw_identity_already_taken_when_google_subject_occupied() {
        // arrange
        User other = new User();
        ReflectionTestUtils.setField(other, "id", 99L); // different from guestUser.id=1L
        when(userRepository.findByEmail("g@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByGoogleSubject("taken-sub")).thenReturn(Optional.of(other));

        // act + assert
        assertThatThrownBy(() -> guestAuthService.convertViaGoogle(guestUser, "g@example.com", "taken-sub"))
                .isInstanceOf(GuestConvertException.class)
                .extracting(e -> ((GuestConvertException) e).getCode())
                .isEqualTo(GuestConvertException.Code.IDENTITY_ALREADY_TAKEN);
    }

    // ─── convertViaApple ─────────────────────────────────────────────────────

    @Test
    void should_convert_guest_to_apple_user_with_null_email_when_private_relay() {
        // arrange — Apple private relay: email = null
        when(userRepository.findByAppleSubject("apple-sub")).thenReturn(Optional.empty());
        when(userRepository.save(guestUser)).thenReturn(guestUser);
        when(tokenService.issuePair(guestUser)).thenReturn(tokenPair);

        // act
        TokenPair result = guestAuthService.convertViaApple(guestUser, null, "apple-sub");

        // assert — email остался null, subject привязан
        assertThat(result).isEqualTo(tokenPair);
        assertThat(guestUser.getAppleSubject()).isEqualTo("apple-sub");
        assertThat(guestUser.getEmail()).isNull();
        assertThat(guestUser.isGuest()).isFalse();
    }

    @Test
    void should_convert_guest_to_apple_user_with_email_when_provided() {
        // arrange
        when(userRepository.findByEmail("a@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByAppleSubject("apple-sub")).thenReturn(Optional.empty());
        when(userRepository.save(guestUser)).thenReturn(guestUser);
        when(tokenService.issuePair(guestUser)).thenReturn(tokenPair);

        // act
        guestAuthService.convertViaApple(guestUser, "a@example.com", "apple-sub");

        // assert
        assertThat(guestUser.getEmail()).isEqualTo("a@example.com");
        assertThat(guestUser.isEmailVerified()).isTrue();
    }
}

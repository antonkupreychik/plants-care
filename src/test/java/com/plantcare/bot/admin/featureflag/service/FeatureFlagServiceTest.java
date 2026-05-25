package com.plantcare.bot.admin.featureflag.service;

import com.plantcare.core.domain.User;
import com.plantcare.core.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FeatureFlagService — issue #78")
class FeatureFlagServiceTest {

    @Mock private UserRepository userRepository;

    @InjectMocks
    private FeatureFlagService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(100L)
                .featureFlags(new HashMap<>())
                .build();
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
    }

    @Nested
    @DisplayName("setFlag")
    class SetFlag {

        @Test
        @DisplayName("Включает флаг: записывает 'true' в map, сохраняет, возвращает true")
        void shouldEnableFlag() {
            boolean changed = service.setFlag(42L, "sharing", true, "admin");

            assertThat(changed).isTrue();
            assertThat(user.getFeatureFlags()).containsEntry("sharing", "true");
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("Повторное включение того же флага → changed=false, save не вызывается")
        void shouldNotSaveOnNoOp() {
            user.getFeatureFlags().put("sharing", "true");

            boolean changed = service.setFlag(42L, "sharing", true, "admin");

            assertThat(changed).isFalse();
            verify(userRepository, never()).save(user);
        }

        @Test
        @DisplayName("setFlag(false) удаляет ключ из map")
        void shouldRemoveKeyOnDisable() {
            user.getFeatureFlags().put("sharing", "true");

            boolean changed = service.setFlag(42L, "sharing", false, "admin");

            assertThat(changed).isTrue();
            assertThat(user.getFeatureFlags()).doesNotContainKey("sharing");
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("Невалидный код → IllegalArgumentException, БД не трогается")
        void shouldRejectInvalidCode() {
            assertThatThrownBy(() -> service.setFlag(42L, "Foo bar!", true, "admin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Невалидный код флага");
            verify(userRepository, never()).save(user);
        }

        @Test
        @DisplayName("Юзер не найден → IllegalArgumentException")
        void shouldFailWhenUserMissing() {
            when(userRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.setFlag(99L, "sharing", true, "admin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Юзер не найден");
        }

        @Test
        @DisplayName("Код приводится к lower-case и trim'ится перед записью")
        void shouldNormalizeCode() {
            service.setFlag(42L, "  SHARING  ", true, "admin");

            assertThat(user.getFeatureFlags()).containsKey("sharing");
            assertThat(user.getFeatureFlags()).doesNotContainKey("SHARING");
        }

        @Test
        @DisplayName("featureFlags == null → инициализируется новой HashMap")
        void shouldInitNullMap() {
            user.setFeatureFlags(null);

            service.setFlag(42L, "sharing", true, "admin");

            assertThat(user.getFeatureFlags()).containsEntry("sharing", "true");
        }
    }

    @Nested
    @DisplayName("enabledFor")
    class EnabledFor {

        @Test
        @DisplayName("Возвращает коды только тех флагов, где value=='true'")
        void shouldReturnTrueOnly() {
            Map<String, Object> flags = new HashMap<>();
            flags.put("a", "true");
            flags.put("b", "false");
            flags.put("c", "true");
            flags.put("d", null);
            user.setFeatureFlags(flags);

            var enabled = service.enabledFor(user);

            assertThat(enabled).containsExactlyInAnyOrder("a", "c");
        }

        @Test
        @DisplayName("Пустая/null map → пустой set")
        void shouldReturnEmpty() {
            user.setFeatureFlags(null);
            assertThat(service.enabledFor(user)).isEmpty();

            user.setFeatureFlags(new HashMap<>());
            assertThat(service.enabledFor(user)).isEmpty();
        }
    }

    @Nested
    @DisplayName("User.hasFeature")
    class HasFeature {

        @Test
        @DisplayName("'true' → true, всё остальное → false")
        void shouldRecognizeOnlyTrue() {
            user.getFeatureFlags().put("a", "true");
            user.getFeatureFlags().put("b", "false");
            user.getFeatureFlags().put("c", 1);

            assertThat(user.hasFeature("a")).isTrue();
            assertThat(user.hasFeature("b")).isFalse();
            assertThat(user.hasFeature("c")).isFalse();
            assertThat(user.hasFeature("nonexistent")).isFalse();
        }

        @Test
        @DisplayName("null code или null map → false без NPE")
        void shouldHandleNulls() {
            assertThat(user.hasFeature(null)).isFalse();
            user.setFeatureFlags(null);
            assertThat(user.hasFeature("anything")).isFalse();
        }
    }
}

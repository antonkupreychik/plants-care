package com.plantcare.admin.broadcast.service;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.AudienceFilter;
import com.plantcare.core.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AudienceResolver — фильтры аудитории (issue #60)")
class AudienceResolverTest {

    @Mock private UserRepository userRepository;

    @InjectMocks
    private AudienceResolver resolver;

    @Test
    @DisplayName("ALL_EXCEPT_BLOCKED → findAllNotBlocked")
    void shouldUseAllNotBlockedQuery() {
        when(userRepository.findAllNotBlocked())
                .thenReturn(List.of(User.builder().telegramChatId(1L).build()));

        var result = resolver.resolve(AudienceFilter.ALL_EXCEPT_BLOCKED, Set.of());

        assertThat(result).hasSize(1);
        verify(userRepository).findAllNotBlocked();
    }

    @Test
    @DisplayName("ONLINE → findOnline с now")
    void shouldUseOnlineQuery() {
        when(userRepository.findOnline(any()))
                .thenReturn(List.of(User.builder().telegramChatId(1L).build(),
                                    User.builder().telegramChatId(2L).build()));

        var result = resolver.resolve(AudienceFilter.ONLINE, null);

        assertThat(result).hasSize(2);
        verify(userRepository).findOnline(any());
    }

    @Test
    @DisplayName("BY_TIMEZONE с пустым set → пустой результат, query не зовётся")
    void shouldReturnEmptyForEmptyTimezones() {
        var result = resolver.resolve(AudienceFilter.BY_TIMEZONE, Set.of());
        assertThat(result).isEmpty();
        verify(userRepository, never()).findByTimezones(anyCollection());
    }

    @Test
    @DisplayName("BY_TIMEZONE с непустым set → findByTimezones")
    void shouldQueryByTimezones() {
        when(userRepository.findByTimezones(anyCollection()))
                .thenReturn(List.of(User.builder().telegramChatId(1L).build()));

        var result = resolver.resolve(AudienceFilter.BY_TIMEZONE,
                Set.of("Europe/Riga", "Europe/Moscow"));

        assertThat(result).hasSize(1);
        verify(userRepository).findByTimezones(anyCollection());
    }

    @Test
    @DisplayName("count для ALL_EXCEPT_BLOCKED делегирует в countAllNotBlocked")
    void shouldCountAll() {
        when(userRepository.countAllNotBlocked()).thenReturn(15L);

        long n = resolver.count(AudienceFilter.ALL_EXCEPT_BLOCKED, Set.of());

        assertThat(n).isEqualTo(15L);
    }

    @Test
    @DisplayName("count BY_TIMEZONE с пустым набором → 0")
    void shouldCountZeroForEmptyTimezones() {
        long n = resolver.count(AudienceFilter.BY_TIMEZONE, Set.of());
        assertThat(n).isEqualTo(0L);
        verify(userRepository, never()).countByTimezones(anyCollection());
    }
}

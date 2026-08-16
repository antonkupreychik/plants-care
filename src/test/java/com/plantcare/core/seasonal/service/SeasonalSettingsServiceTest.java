package com.plantcare.core.seasonal.service;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.Season;
import com.plantcare.core.domain.enums.SeasonalMode;
import com.plantcare.core.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SeasonalSettingsService} — per-season setting mutation,
 * clamping and clearing logic (issue #256). Repository is mocked; the service
 * itself has no persistence side effects beyond {@code save}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeasonalSettingsServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private SeasonalSettingsService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .seasonalEnabled(true)
                .seasonalMode(SeasonalMode.MULTIPLIER)
                .summerMultiplier(new BigDecimal("0.80"))
                .winterMultiplier(new BigDecimal("1.20"))
                .build();
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void should_set_multiplier_rounded_to_two_decimals_when_update_multiplier() {
        service.updateSeason(user, Season.SUMMER, new BigDecimal("0.666"), null);

        assertThat(user.getSummerMultiplier()).isEqualByComparingTo(new BigDecimal("0.67"));
    }

    @Test
    void should_clamp_multiplier_to_max_when_above_range() {
        service.updateSeason(user, Season.WINTER, new BigDecimal("9.99"), null);

        assertThat(user.getWinterMultiplier())
                .isEqualByComparingTo(SeasonalSettingsService.MAX_MULTIPLIER);
    }

    @Test
    void should_clamp_interval_to_bounds_when_above_max() {
        service.updateSeason(user, Season.SUMMER, null, 999);

        assertThat(user.getSummerIntervalOverrideDays())
                .isEqualTo(SeasonalIntervalService.MAX_INTERVAL_DAYS);
    }

    @Test
    void should_clear_interval_to_null_when_clear_called() {
        user.setSummerIntervalOverrideDays(7);

        service.clearInterval(user, Season.SUMMER);

        assertThat(user.getSummerIntervalOverrideDays()).isNull();
    }

    @Test
    void should_reject_update_when_no_field_provided() {
        assertThatThrownBy(() -> service.updateSeason(user, Season.SUMMER, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_return_both_seasons_when_get_settings() {
        var settings = service.getSettings(user);

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.mode()).isEqualTo(SeasonalMode.MULTIPLIER);
        List<Season> seasons = settings.seasons().stream()
                .map(SeasonalSettingsService.SeasonSetting::season).toList();
        assertThat(seasons).containsExactly(Season.SUMMER, Season.WINTER);
    }
}

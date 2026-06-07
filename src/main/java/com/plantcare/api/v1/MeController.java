package com.plantcare.api.v1;

import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.generated.MeApi;
import com.plantcare.api.generated.model.MeResponse;
import com.plantcare.api.generated.model.MeUpdateRequest;
import com.plantcare.api.generated.model.Season;
import com.plantcare.api.generated.model.SeasonSettings;
import com.plantcare.api.generated.model.SeasonalSettingsResponse;
import com.plantcare.api.generated.model.SeasonalSettingsUpdateRequest;
import com.plantcare.api.generated.model.WeatherLocationRequest;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.SeasonalMode;
import com.plantcare.core.seasonal.service.SeasonalSettingsService;
import com.plantcare.core.seasonal.service.SeasonalSettingsService.SeasonSetting;
import com.plantcare.core.seasonal.service.SeasonalSettingsService.SeasonalSettings;
import com.plantcare.core.service.AccountDeletionService;
import com.plantcare.core.service.UserProfileService;
import com.plantcare.core.service.UserProfileService.Profile;
import com.plantcare.core.service.UserProfileService.ProfileUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * REST API профиля и настроек текущего пользователя (issue #182, mobile G5 + G16).
 *
 * <p>Документация и валидация полей живут в сгенерированном {@link MeApi}.
 * DELETE /api/v1/me добавлен в issue #181 (GDPR / App Store).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MeController implements MeApi {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final UserProfileService userProfileService;
    private final CurrentUserProvider currentUserProvider;
    private final AccountDeletionService accountDeletionService;
    private final SeasonalSettingsService seasonalSettingsService;

    @Override
    public MeResponse getMe() {
        User user = currentUserProvider.currentUser();
        log.info("GET /api/v1/me: userId={}", user.getId());

        return toResponse(userProfileService.getProfile(user));
    }

    @Override
    public void deleteMe() {
        // currentUserId() используется вместо currentUser() намеренно: пользователь мог уже
        // быть удалён (повторный вызов — идемпотентность), поэтому не бросаем 404 через currentUser().
        Long userId = currentUserProvider.currentUserId();
        log.info("DELETE /api/v1/me: userId={}", userId);
        accountDeletionService.deleteAccount(userId);
    }

    @Override
    public void updateWeatherLocation(WeatherLocationRequest request) {
        User user = currentUserProvider.currentUser();
        log.info("PUT /api/v1/me/weather-location: userId={}, lat={}, lon={}",
                user.getId(), request.getLat(), request.getLon());
        userProfileService.setWeatherLocation(user, request.getLat(), request.getLon());
    }

    @Override
    public MeResponse updateMe(MeUpdateRequest request) {
        User user = currentUserProvider.currentUser();
        log.info("PATCH /api/v1/me: userId={}", user.getId());

        ProfileUpdate update = new ProfileUpdate(
                parseTime(request.getQuietHoursStart()),
                parseTime(request.getQuietHoursEnd()),
                request.getTimezone(),
                request.getLocale(),
                request.getSeasonalEnabled(),
                toSeasonalMode(request.getSeasonalMode()),
                request.getWeatherEnabled()
        );

        return toResponse(userProfileService.updateProfile(user, update));
    }

    // ===================================================================
    // Per-season настройки (issue #256): /api/v1/me/seasonal
    // ===================================================================

    @Override
    public SeasonalSettingsResponse getSeasonalSettings() {
        User user = currentUserProvider.currentUser();
        log.info("GET /api/v1/me/seasonal: userId={}", user.getId());

        return toSeasonalResponse(seasonalSettingsService.getSettings(user));
    }

    @Override
    public SeasonalSettingsResponse updateSeasonalSettings(SeasonalSettingsUpdateRequest request) {
        User user = currentUserProvider.currentUser();
        log.info("PATCH /api/v1/me/seasonal: userId={}, season={}", user.getId(), request.getSeason());

        SeasonalSettings settings = seasonalSettingsService.updateSeason(
                user,
                toDomainSeason(request.getSeason()),
                toMultiplier(request.getMultiplier()),
                request.getIntervalDays());
        return toSeasonalResponse(settings);
    }

    @Override
    public SeasonalSettingsResponse clearSeasonalInterval(Season season) {
        User user = currentUserProvider.currentUser();
        log.info("DELETE /api/v1/me/seasonal/{}: userId={}", season, user.getId());

        return toSeasonalResponse(seasonalSettingsService.clearInterval(user, toDomainSeason(season)));
    }

    private static SeasonalSettingsResponse toSeasonalResponse(SeasonalSettings settings) {
        return new SeasonalSettingsResponse(
                settings.enabled(),
                SeasonalSettingsResponse.ModeEnum.fromValue(settings.mode().name()),
                settings.seasons().stream().map(MeController::toSeasonSettings).toList());
    }

    private static SeasonSettings toSeasonSettings(SeasonSetting setting) {
        // multiplier обязателен в схеме (NUMERIC NOT NULL в БД); intervalDays опционален.
        return new SeasonSettings(
                Season.fromValue(setting.season().name()),
                setting.multiplier().doubleValue())
                .intervalDays(setting.intervalDays());
    }

    private static com.plantcare.core.domain.enums.Season toDomainSeason(Season season) {
        return com.plantcare.core.domain.enums.Season.valueOf(season.getValue());
    }

    private static BigDecimal toMultiplier(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static MeResponse toResponse(Profile profile) {
        return new MeResponse(
                profile.id(),
                profile.emailVerified(),
                profile.createdAt(),
                profile.name(),
                profile.plantsTotal(),
                profile.tasksToday(),
                profile.totalCareEvents(),
                profile.notificationsUnread(),
                profile.quietHoursStart().format(HH_MM),
                profile.quietHoursEnd().format(HH_MM),
                profile.timezone(),
                MeResponse.LocaleEnum.fromValue(profile.locale()),
                profile.seasonalEnabled(),
                MeResponse.SeasonalModeEnum.fromValue(profile.seasonalMode().name()),
                profile.weatherEnabled(),
                profile.featureFlags(),
                profile.appleLinked(),
                profile.googleLinked(),
                profile.emailLinked(),
                profile.telegramLinked(),
                profile.isGuest()
        )
                .email(profile.email())
                .avatar(profile.avatar())
                .calendarSubscriptionUrl(profile.calendarSubscriptionUrl());
    }

    private static SeasonalMode toSeasonalMode(String value) {
        // @Pattern на DTO гарантирует MULTIPLIER|FIXED до вызова сервиса, поэтому valueOf безопасен.
        return value == null ? null : SeasonalMode.valueOf(value);
    }

    private static LocalTime parseTime(String hhmm) {
        return hhmm == null ? null : LocalTime.parse(hhmm, HH_MM);
    }
}

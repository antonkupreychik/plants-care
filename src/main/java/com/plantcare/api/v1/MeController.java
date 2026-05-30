package com.plantcare.api.v1;

import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.generated.MeApi;
import com.plantcare.api.generated.model.MeResponse;
import com.plantcare.api.generated.model.MeUpdateRequest;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.SeasonalMode;
import com.plantcare.core.service.UserProfileService;
import com.plantcare.core.service.UserProfileService.Profile;
import com.plantcare.core.service.UserProfileService.ProfileUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * REST API профиля и настроек текущего пользователя (issue #182, mobile G5 + G16).
 *
 * <p>Документация и валидация полей живут в сгенерированном {@link MeApi}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MeController implements MeApi {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final UserProfileService userProfileService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public MeResponse getMe() {
        User user = currentUserProvider.currentUser();
        log.info("GET /api/v1/me: userId={}", user.getId());

        return toResponse(userProfileService.getProfile(user));
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

    private static MeResponse toResponse(Profile profile) {
        return new MeResponse(
                profile.id(),
                profile.emailVerified(),
                profile.createdAt(),
                profile.name(),
                profile.plantsTotal(),
                profile.tasksToday(),
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
                profile.telegramLinked()
        )
                .email(profile.email())
                .avatar(profile.avatar());
    }

    private static SeasonalMode toSeasonalMode(String value) {
        // @Pattern на DTO гарантирует MULTIPLIER|FIXED до вызова сервиса, поэтому valueOf безопасен.
        return value == null ? null : SeasonalMode.valueOf(value);
    }

    private static LocalTime parseTime(String hhmm) {
        return hhmm == null ? null : LocalTime.parse(hhmm, HH_MM);
    }
}

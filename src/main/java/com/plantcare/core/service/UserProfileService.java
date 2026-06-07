package com.plantcare.core.service;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.SeasonalMode;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Профиль и настройки пользователя для REST API {@code /api/v1/me} (issue #182,
 * mobile G5 + G16).
 *
 * <p>Сборка профиля переиспользует существующие источники, а не дублирует логику:
 * число неархивированных растений — {@link PlantRepository#countByUserIdAndArchivedAtIsNull(Long)},
 * число задач на сегодня — pending-часть {@link TodayApiService#getTodayTasks(Long, String)}
 * (выдача {@code GET /api/v1/today}, отфильтрованная по {@code doneAt == null}; считается в TZ пользователя).
 *
 * <p>Смена таймзоны делегируется {@link UserSettingsService#changeTimezone(User, ZoneId)},
 * чтобы сохранить пересчёт активных расписаний (локальное время дня не «съезжает»).
 * Внешних вызовов (Telegram) внутри транзакции нет — только чтение/запись БД.
 *
 * <p>{@code calendarSubscriptionUrl} читается из уже существующего {@code calendar_token} юзера
 * (lazy — токен генерируется только при первом обращении к {@code GET /calendar/{token}.ics}).
 * При {@code null}-токене поле {@code calendarSubscriptionUrl} тоже {@code null} (issue #208).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserRepository userRepository;
    private final PlantRepository plantRepository;
    private final TodayApiService todayApiService;
    private final UserSettingsService userSettingsService;
    private final CareHistoryService careHistoryService;
    private final CalendarExportService calendarExportService;

    /**
     * Собранный профиль пользователя для {@code GET /api/v1/me}.
     *
     * <p>{@code avatar} — плейсхолдер: в схеме нет колонки под аватар, поэтому
     * всегда {@code null}. {@code notificationsUnread} — плейсхолдер {@code 0}:
     * фид уведомлений (issue #183) ещё не влит в эту ветку.
     * <p>{@code calendarSubscriptionUrl} — {@code null}, если у юзера нет токена
     * (токен создаётся лениво при первом обращении к эндпоинту {@code .ics}).
     */
    public record Profile(
            long id,
            String email,
            boolean emailVerified,
            OffsetDateTime createdAt,
            String name,
            String avatar,
            int plantsTotal,
            int tasksToday,
            long totalCareEvents,
            int notificationsUnread,
            LocalTime quietHoursStart,
            LocalTime quietHoursEnd,
            String timezone,
            String locale,
            boolean seasonalEnabled,
            SeasonalMode seasonalMode,
            boolean weatherEnabled,
            Map<String, Object> featureFlags,
            boolean appleLinked,
            boolean googleLinked,
            boolean emailLinked,
            boolean telegramLinked,
            boolean isGuest,
            String calendarSubscriptionUrl
    ) {
    }

    /**
     * Частичный апдейт настроек: {@code null}-поле = «не менять».
     * {@code timezone} — IANA-идентификатор, {@code locale} — {@code ru}|{@code en}.
     * {@code seasonalEnabled}/{@code seasonalMode}/{@code weatherEnabled} — простые
     * тогглы (read-only для остальных полей профиля).
     */
    public record ProfileUpdate(
            LocalTime quietHoursStart,
            LocalTime quietHoursEnd,
            String timezone,
            String locale,
            Boolean seasonalEnabled,
            SeasonalMode seasonalMode,
            Boolean weatherEnabled
    ) {
    }

    /** Собирает профиль и счётчики текущего пользователя. */
    @Transactional(readOnly = true)
    public Profile getProfile(User user) {
        int plantsTotal = Math.toIntExact(
                plantRepository.countByUserIdAndArchivedAtIsNull(user.getId()));
        // tasksToday — только pending (дедлайн до конца сегодняшнего дня в TZ юзера).
        // getTodayTasks возвращает UNION pending + done-сегодня (issue #184), поэтому
        // отбрасываем выполненные (doneAt != null), чтобы бейдж не рос по мере отметок.
        int tasksToday = (int) todayApiService.getTodayTasks(user.getId(), user.getTimezone())
                .stream()
                .filter(t -> t.doneAt() == null)
                .count();

        long totalCareEvents = careHistoryService.countAllByUser(user.getId());

        // notificationsUnread — плейсхолдер: фид уведомлений (issue #183) ещё не влит.
        int notificationsUnread = 0;

        // createdAt в БД — LocalDateTime в UTC wall-clock; отдаём наружу как UTC OffsetDateTime.
        OffsetDateTime createdAt = user.getCreatedAt() == null
                ? null
                : user.getCreatedAt().atOffset(ZoneOffset.UTC);

        // calendarSubscriptionUrl — только если токен уже существует (не создаём его здесь,
        // read-only транзакция). Токен генерируется лениво при первом обращении к .ics.
        String calendarSubscriptionUrl = user.getCalendarToken() != null
                ? calendarExportService.buildCalendarUrl(user.getCalendarToken())
                : null;

        return new Profile(
                user.getId(),
                user.getEmail(),
                user.isEmailVerified(),
                createdAt,
                user.getUsername(),
                null, // avatar — плейсхолдер, колонки в схеме нет.
                plantsTotal,
                tasksToday,
                totalCareEvents,
                notificationsUnread,
                user.getQuietHoursStart(),
                user.getQuietHoursEnd(),
                user.getTimezone(),
                user.getLocale(),
                user.isSeasonalEnabled(),
                user.getSeasonalMode(),
                user.isWeatherEnabled(),
                Map.copyOf(user.getFeatureFlags()),
                user.getAppleSubject() != null,
                user.getGoogleSubject() != null,
                user.getEmail() != null,
                user.getTelegramChatId() != null,
                user.isGuest(),
                calendarSubscriptionUrl
        );
    }

    /**
     * Применяет только переданные ({@code != null}) поля и возвращает обновлённый
     * профиль. Невалидный IANA-{@code timezone} → {@link IllegalArgumentException}
     * (маппится на 400). Формат {@code quietHours*}/{@code locale} проверяется
     * Bean Validation на сгенерированном DTO до вызова сервиса.
     */
    @Transactional
    public Profile updateProfile(User user, ProfileUpdate update) {
        // Тихие часы: эффективные значения с учётом текущих (если передано только одно поле).
        // Совпадение начала и конца недопустимо (пустой/неоднозначный интервал) → 400.
        LocalTime effectiveStart = update.quietHoursStart() != null
                ? update.quietHoursStart() : user.getQuietHoursStart();
        LocalTime effectiveEnd = update.quietHoursEnd() != null
                ? update.quietHoursEnd() : user.getQuietHoursEnd();
        if ((update.quietHoursStart() != null || update.quietHoursEnd() != null)
                && effectiveStart.equals(effectiveEnd)) {
            throw new IllegalArgumentException(
                    "quietHoursStart must not equal quietHoursEnd: " + effectiveStart);
        }

        if (update.quietHoursStart() != null) {
            userSettingsService.setQuietHoursStart(user, update.quietHoursStart());
        }
        if (update.quietHoursEnd() != null) {
            userSettingsService.setQuietHoursEnd(user, update.quietHoursEnd());
        }
        if (update.timezone() != null) {
            ZoneId zone = parseZone(update.timezone());
            userSettingsService.changeTimezone(user, zone);
        }
        if (update.locale() != null) {
            user.setLocale(update.locale());
        }
        // Сезонность/погода — простые тогглы, влияют только на ленивый расчёт next_due_at,
        // пересчёт расписаний не нужен (в отличие от смены таймзоны).
        if (update.seasonalEnabled() != null) {
            user.setSeasonalEnabled(update.seasonalEnabled());
        }
        if (update.seasonalMode() != null) {
            user.setSeasonalMode(update.seasonalMode());
        }
        if (update.weatherEnabled() != null) {
            user.setWeatherEnabled(update.weatherEnabled());
        }
        userRepository.save(user);

        log.info("PATCH /me applied: userId={}, tzChanged={}, localeChanged={}, "
                        + "seasonalEnabledChanged={}, seasonalModeChanged={}, weatherEnabledChanged={}",
                user.getId(), update.timezone() != null, update.locale() != null,
                update.seasonalEnabled() != null, update.seasonalMode() != null,
                update.weatherEnabled() != null);

        return getProfile(user);
    }

    private static ZoneId parseZone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Invalid IANA timezone: " + timezone, e);
        }
    }
}

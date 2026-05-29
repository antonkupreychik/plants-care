package com.plantcare.core.service;

import com.plantcare.core.domain.User;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;

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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserRepository userRepository;
    private final PlantRepository plantRepository;
    private final TodayApiService todayApiService;
    private final UserSettingsService userSettingsService;

    /**
     * Собранный профиль пользователя для {@code GET /api/v1/me}.
     *
     * <p>{@code avatar} — плейсхолдер: в схеме нет колонки под аватар, поэтому
     * всегда {@code null}. {@code notificationsUnread} — плейсхолдер {@code 0}:
     * фид уведомлений (issue #183) ещё не влит в эту ветку.
     */
    public record Profile(
            String name,
            String avatar,
            int plantsTotal,
            int tasksToday,
            int notificationsUnread,
            LocalTime quietHoursStart,
            LocalTime quietHoursEnd,
            String timezone,
            String locale
    ) {
    }

    /**
     * Частичный апдейт настроек: {@code null}-поле = «не менять».
     * {@code timezone} — IANA-идентификатор, {@code locale} — {@code ru}|{@code en}.
     */
    public record ProfileUpdate(
            LocalTime quietHoursStart,
            LocalTime quietHoursEnd,
            String timezone,
            String locale
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

        // notificationsUnread — плейсхолдер: фид уведомлений (issue #183) ещё не влит.
        int notificationsUnread = 0;

        return new Profile(
                user.getUsername(),
                null, // avatar — плейсхолдер, колонки в схеме нет.
                plantsTotal,
                tasksToday,
                notificationsUnread,
                user.getQuietHoursStart(),
                user.getQuietHoursEnd(),
                user.getTimezone(),
                user.getLocale()
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
            userRepository.save(user);
        }

        log.info("PATCH /me applied: userId={}, tzChanged={}, localeChanged={}",
                user.getId(), update.timezone() != null, update.locale() != null);

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

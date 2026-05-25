package com.plantcare.core.service;

import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Настройки пользователя из меню «⚙️ Настройки» (issue #116): смена таймзоны
 * и редактирование тихих часов.
 *
 * <p>Ключевой момент — смена таймзоны пересчитывает {@code nextDueAt} активных
 * расписаний так, чтобы «локальное время дня» сохранилось (полить в 9:00 в Минске
 * остаётся 9:00 в Алматы). {@code nextDueAt} хранится как UTC wall-clock
 * {@link LocalDateTime} (так же пишет {@link UserService#startVacation}), поэтому
 * конвертация проходит через {@link ZoneOffset#UTC}.
 *
 * <p>Telegram-вызовов внутри транзакции нет (см. CLAUDE.md). Все методы только
 * меняют состояние БД и возвращают данные для последующей отправки наружу.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserSettingsService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final UserRepository userRepository;
    private final CareScheduleRepository careScheduleRepository;
    private final QuietHoursPolicy quietHoursPolicy;

    /**
     * Результат смены таймзоны для построения подтверждающего сообщения.
     *
     * @param newZoneId          новый ZoneId юзера (например {@code Asia/Almaty})
     * @param rescheduledCount   сколько активных расписаний пересчитано
     * @param quietHoursConflict есть ли среди пересчитанных хотя бы одно, чей новый
     *                           момент попадает в тихие часы юзера
     */
    public record TimezoneChangeResult(String newZoneId, int rescheduledCount, boolean quietHoursConflict) {
    }

    /**
     * Меняет таймзону юзера и пересчитывает {@code nextDueAt} активных расписаний,
     * сохраняя локальное время дня в новой зоне.
     */
    @Transactional
    public TimezoneChangeResult changeTimezone(User user, ZoneId newZone) {
        ZoneId oldZone = resolveZone(user.getTimezone());

        user.setTimezone(newZone.getId());

        List<CareSchedule> schedules = careScheduleRepository.findActiveSchedulesByUserId(user.getId());

        boolean quietHoursConflict = false;

        for (CareSchedule schedule : schedules) {
            LocalDateTime oldNext = schedule.getNextDueAt();
            if (oldNext == null) {
                continue;
            }

            Instant oldInstant = oldNext.toInstant(ZoneOffset.UTC);
            ZonedDateTime oldLocal = oldInstant.atZone(oldZone);
            Instant newInstant = ZonedDateTime.of(oldLocal.toLocalDate(), oldLocal.toLocalTime(), newZone)
                    .toInstant();

            schedule.setNextDueAt(LocalDateTime.ofInstant(newInstant, ZoneOffset.UTC));

            // user.timezone уже = newZone, поэтому isQuiet считает в новой зоне.
            if (quietHoursPolicy.isQuiet(user, newInstant)) {
                quietHoursConflict = true;
            }
        }

        userRepository.save(user);
        careScheduleRepository.saveAll(schedules);

        log.info("Timezone changed to {} for user {}, rescheduled {} schedules (quietConflict={})",
                newZone.getId(), user.getTelegramChatId(), schedules.size(), quietHoursConflict);

        return new TimezoneChangeResult(newZone.getId(), schedules.size(), quietHoursConflict);
    }

    /**
     * Устанавливает начало тихих часов. Пересчёта расписаний не делаем — тихие часы
     * применяются на этапе отправки, не хранения.
     */
    @Transactional
    public void setQuietHoursStart(User user, LocalTime start) {
        user.setQuietHoursStart(start);
        userRepository.save(user);
        log.info("Quiet hours start set to {} for user {}", start, user.getTelegramChatId());
    }

    @Transactional
    public void setQuietHoursEnd(User user, LocalTime end) {
        user.setQuietHoursEnd(end);
        userRepository.save(user);
        log.info("Quiet hours end set to {} for user {}", end, user.getTelegramChatId());
    }

    @Transactional
    public void resetQuietHours(User user) {
        user.setQuietHoursStart(LocalTime.of(22, 0));
        user.setQuietHoursEnd(LocalTime.of(9, 0));
        userRepository.save(user);
        log.info("Quiet hours reset to 22:00-09:00 for user {}", user.getTelegramChatId());
    }

    /**
     * Строит текст подтверждения смены таймзоны для отправки наружу (после транзакции).
     * Учитывает наличие пересчитанных расписаний и конфликт с тихими часами.
     */
    public static String buildTimezoneConfirmation(TimezoneChangeResult result, LocalTime quietHoursEnd) {
        StringBuilder sb = new StringBuilder();

        if (result.rescheduledCount() > 0) {
            sb.append("🌐 Таймзона: ").append(result.newZoneId()).append(". Расписания пересчитаны.");
        } else {
            sb.append("✅ Часовой пояс обновлён: ").append(result.newZoneId());
        }

        if (result.quietHoursConflict() && quietHoursEnd != null) {
            sb.append("\n⚠️ У тебя есть напоминания в тихие часы — они приедут в ")
                    .append(quietHoursEnd.format(TIME_FMT))
                    .append(".");
        }

        return sb.toString();
    }

    private ZoneId resolveZone(String tz) {
        if (tz == null || tz.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            log.warn("Invalid timezone '{}', defaulting to UTC", tz);
            return ZoneOffset.UTC;
        }
    }
}

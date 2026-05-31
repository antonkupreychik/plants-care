package com.plantcare.core.service;

import com.plantcare.core.domain.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Политика «тихих часов» юзера (quiet hours). Централизует две операции:
 * проверку «попадает ли момент в quiet-hours» и сдвиг кандидата на ближайший
 * момент за пределами quiet-hours.
 *
 * <p>Раньше эта логика жила приватно в {@link NotificationSchedulerService},
 * но с появлением snooze-выбора (issue #118) такой же расчёт нужен из
 * {@link NotificationCallbackService}: если юзер выбрал «через час» и попал
 * в свои тихие часы, мы должны сдвинуть напоминание на конец quiet-окна.
 * Дублировать логику в двух местах опасно — разъедутся правила.
 *
 * <p>Все методы безопасны к невалидной TZ юзера: при ошибке парсинга
 * откатываемся на UTC и логируем warn.
 */
@Slf4j
@Component
public class QuietHoursPolicy {

    /**
     * Если {@code candidate} попадает в quiet-hours юзера — возвращает ближайший
     * {@link Instant} ≥ {@code candidate}, который из quiet-hours уже вышел
     * (момент, равный {@code quietHoursEnd} в TZ юзера). Иначе возвращает
     * {@code candidate} как есть.
     *
     * <p>Поведение по краям:
     * <ul>
     *   <li>{@code start == end} — quiet-hours не настроены, candidate без изменений.</li>
     *   <li>candidate ровно в момент {@code end} — НЕ в quiet-hours
     *       (полуоткрытое окно {@code [start; end)}), возвращается как есть.</li>
     *   <li>Если quiet-hours переходят через полночь и candidate попадает
     *       до полуночи — {@code end} берётся на следующий локальный день.</li>
     * </ul>
     *
     * @return сдвинутый или исходный момент; никогда не {@code null}, если на вход
     *         не {@code null}
     */
    public Instant shiftOutOfQuietHours(User user, Instant candidate) {
        if (candidate == null) {
            return null;
        }
        ZoneId zone = resolveZone(user);
        LocalTime start = user.getQuietHoursStart();
        LocalTime end = user.getQuietHoursEnd();

        if (start == null || end == null || start.equals(end)) {
            return candidate;
        }

        ZonedDateTime local = candidate.atZone(zone);
        LocalTime t = local.toLocalTime();

        if (!inQuietWindow(start, end, t)) {
            return candidate;
        }

        // Находим конец quiet-окна в той же TZ. Для overnight-окна (например
        // 22:00 → 09:00) endDate = следующий день, если candidate ≥ start;
        // если candidate ещё до end (например 02:00) — endDate = тот же день.
        LocalDate endDate = local.toLocalDate();
        if (start.isAfter(end) && !t.isBefore(start)) {
            endDate = endDate.plusDays(1);
        }
        return ZonedDateTime.of(endDate, end, zone).toInstant();
    }

    /**
     * Попадает ли указанный момент в quiet-hours юзера (по {@link Instant}).
     * Используется snooze-flow, который оперирует абсолютным временем.
     */
    public boolean isQuiet(User user, Instant instant) {
        ZoneId zone = resolveZone(user);
        LocalTime userTime = instant.atZone(zone).toLocalTime();
        return inQuietWindow(user.getQuietHoursStart(), user.getQuietHoursEnd(), userTime);
    }

    private boolean inQuietWindow(LocalTime start, LocalTime end, LocalTime t) {
        if (start == null || end == null || start.equals(end)) {
            return false;
        }
        if (start.isBefore(end)) {
            // обычное окно: [start; end)
            return !t.isBefore(start) && t.isBefore(end);
        }
        // overnight: [start; 24:00) ∪ [00:00; end)
        return !t.isBefore(start) || t.isBefore(end);
    }

    private ZoneId resolveZone(User user) {
        if (user == null) return ZoneOffset.UTC;
        String tz = user.getTimezone();
        if (tz == null || tz.isBlank()) return ZoneOffset.UTC;
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            log.warn("Invalid timezone '{}' for user {}, defaulting to UTC",
                    tz, user.getTelegramChatId());
            return ZoneOffset.UTC;
        }
    }
}

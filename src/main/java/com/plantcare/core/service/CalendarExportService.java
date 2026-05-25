package com.plantcare.core.service;

import com.plantcare.core.config.CalendarProperties;
import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

/**
 * Issue #79: экспорт расписаний пользователя в .ics-календарь
 * (Read-Only subscription, All-Day events).
 *
 * <p>Ответственности:
 * <ul>
 *   <li>Лениво генерит и сохраняет уникальный {@code calendar_token} в {@link User}.</li>
 *   <li>Резолвит токен в пользователя и рендерит .ics со всеми его расписаниями
 *       на ближайшие {@link CalendarProperties#eventWindowDays()} дней.</li>
 *   <li>Строит публичную URL вида {@code https://<host>/calendar/{token}.ics}.</li>
 * </ul>
 *
 * <p>RRULE не используем: каждое {@link CareSchedule#getNextDueAt() nextDueAt}
 * проецируется ровно в одно VEVENT.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CalendarExportService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 32 случайных байта -> 43 символа URL-safe base64 без паддинга. Помещается в VARCHAR(64). */
    private static final int TOKEN_BYTES = 32;

    private static final DateTimeFormatter ICS_DT_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter ICS_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final UserRepository userRepository;
    private final CareScheduleRepository careScheduleRepository;
    private final CalendarProperties calendarProperties;
    private final Clock clock;

    /**
     * Возвращает уже сохранённый токен пользователя либо генерирует, сохраняет и возвращает новый.
     * Транзакция должна закрыться до отправки сообщения в Telegram.
     *
     * <p>Берёт row-lock на запись юзера, чтобы при двух параллельных нажатиях
     * на кнопку второй вызов увидел уже сгенерированный первым токен и не
     * перезаписал его новым значением.
     */
    @Transactional
    public String getOrCreateToken(User user) {
        User locked = userRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + user.getId()));
        if (locked.getCalendarToken() != null) {
            return locked.getCalendarToken();
        }
        String token = generateToken();
        locked.setCalendarToken(token);
        userRepository.save(locked);
        log.info("Generated calendar token for userId={}", locked.getId());
        return token;
    }

    /**
     * Резолвит токен и рендерит ICS на ближайшие {@link CalendarProperties#eventWindowDays()} дней.
     * Бросает {@link EntityNotFoundException}, если токен не найден — контроллер маппит в 404.
     */
    @Transactional(readOnly = true)
    public String buildIcsForToken(String token) {
        User user = userRepository.findByCalendarToken(token)
                .orElseThrow(() -> new EntityNotFoundException("Calendar token not found"));

        LocalDateTime from = LocalDateTime.now(clock);
        LocalDateTime to = from.plusDays(calendarProperties.eventWindowDays());

        List<CareSchedule> schedules = careScheduleRepository.findUpcomingForUser(user.getId(), from, to);

        log.debug("Rendering ICS for userId={} events={}", user.getId(), schedules.size());
        return renderIcs(schedules);
    }

    /** Полный публичный URL подписки. */
    public String buildCalendarUrl(String token) {
        return calendarProperties.baseUrl() + "/calendar/" + token + ".ics";
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String renderIcs(List<CareSchedule> schedules) {
        String dtStamp = LocalDateTime.now(clock).atOffset(ZoneOffset.UTC).format(ICS_DT_STAMP);

        StringBuilder sb = new StringBuilder(256 + schedules.size() * 256);
        sb.append("BEGIN:VCALENDAR\r\n");
        sb.append("VERSION:2.0\r\n");
        sb.append("PRODID:-//PlantsCare//Bot Calendar//EN\r\n");
        sb.append("CALSCALE:GREGORIAN\r\n");
        sb.append("METHOD:PUBLISH\r\n");
        sb.append("X-WR-CALNAME:")
                .append(escapeIcsText("Plants Care — уход за растениями"))
                .append("\r\n");

        for (CareSchedule schedule : schedules) {
            appendVEvent(sb, schedule, dtStamp);
        }

        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    private void appendVEvent(StringBuilder sb, CareSchedule schedule, String dtStamp) {
        LocalDate eventDate = schedule.getNextDueAt().toLocalDate();
        String dtStart = eventDate.format(ICS_DATE);
        String dtEnd = eventDate.plusDays(1).format(ICS_DATE);

        String summary = taskTypeSummary(schedule.getTaskType()) + " " + schedule.getPlant().getName();
        String description = taskTypeDescription(schedule.getTaskType());

        sb.append("BEGIN:VEVENT\r\n");
        sb.append("UID:plants-care-schedule-").append(schedule.getId()).append("@plants-care\r\n");
        sb.append("DTSTAMP:").append(dtStamp).append("\r\n");
        sb.append("DTSTART;VALUE=DATE:").append(dtStart).append("\r\n");
        sb.append("DTEND;VALUE=DATE:").append(dtEnd).append("\r\n");
        sb.append("SUMMARY:").append(escapeIcsText(summary)).append("\r\n");
        sb.append("DESCRIPTION:").append(escapeIcsText(description)).append("\r\n");
        sb.append("TRANSP:TRANSPARENT\r\n");
        sb.append("END:VEVENT\r\n");
    }

    /**
     * Экранирует спецсимволы по RFC 5545 §3.3.11.
     * Порядок важен: сначала бэкслеш, потом всё остальное.
     */
    static String escapeIcsText(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
    }

    private static String taskTypeSummary(TaskType type) {
        return switch (type) {
            case WATERING -> "💧 Полив";
            case MISTING -> "💨 Опрыскивание";
            case FERTILIZING -> "🌱 Удобрение";
            case SOIL_CHECK -> "🌍 Проверка грунта";
        };
    }

    private static String taskTypeDescription(TaskType type) {
        return switch (type) {
            case WATERING -> "Время полить растение";
            case MISTING -> "Время опрыскать листья";
            case FERTILIZING -> "Время удобрить";
            case SOIL_CHECK -> "Проверить влажность грунта";
        };
    }
}

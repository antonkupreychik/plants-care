package com.plantcare.core.service;

import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.CareHistoryRepository.PlantActionCount;
import com.plantcare.core.repository.CareHistoryRepository.TaskTypeCount;
import com.plantcare.core.repository.MonthlyReportSentRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Сервис месячного отчёта по уходу (issue #137) — раз в месяц активный юзер
 * получает retention-карточку с итогами за прошлый месяц: всего действий,
 * разбивка по типам ухода, самое старое живое растение и его возраст, растение,
 * которое юзер ни разу не пропустил.
 *
 * <p>Отчётный месяц считается по календарю в TZ юзера ({@link User#getTimezone}),
 * а не в UTC — это даёт устойчивость к моменту запуска шедулера на стыке месяцев.
 * Границы периода: {@code [начало 1-го числа прошлого месяца 00:00 локально;
 * начало текущего месяца 00:00 локально)}, обе границы конвертируются в UTC
 * {@link LocalDateTime} для запроса по {@code care_history.done_at}.
 *
 * <p>Если за месяц меньше {@link CareHistoryService#MIN_ACTIONS_FOR_STATS}
 * действий — отчёт не формируется (порог из #51).
 *
 * <p>Идемпотентность реализована таблицей {@code monthly_report_sent}
 * (PK {@code (user_id, year_month)}). Telegram-вызов остаётся за рамками сервиса:
 * сервис подбирает кандидатов в read-only транзакции и для каждого готовит
 * {@link MonthlyReport}-DTO с готовым текстом, чтобы шедулер отправил отчёт вне
 * транзакции. После успешной отправки шедулер дёргает {@link #markSent} в
 * отдельной транзакции.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonthlyReportService {

    /** Минимум действий за месяц, ниже которого отчёт не шлётся (порог из #51). */
    static final int MIN_ACTIONS = CareHistoryService.MIN_ACTIONS_FOR_STATS;

    /** Час дня (локальный), с которого отчёт считается уместным. */
    private static final int MORNING_HOUR_START = 9;
    /** Час дня (локальный), до которого отчёт считается уместным (exclusive). */
    private static final int MORNING_HOUR_END = 10;

    private final UserRepository userRepository;
    private final PlantRepository plantRepository;
    private final CareHistoryRepository careHistoryRepository;
    private final MonthlyReportSentRepository sentRepository;

    /**
     * Подобрать юзеров, которым сейчас (в TZ юзера) пора отправить отчёт за
     * прошлый месяц, и для каждого собрать метрики и готовый текст.
     *
     * <p>Фильтр «локально 1-е число месяца + утреннее окно [09:00, 10:00)»
     * применяется здесь, в сервисе, и притом ПЕРВЫМ — до любых запросов по
     * {@code monthly_report_sent} и тяжёлых агрегатов по {@code care_history}.
     * В обычный час/день предикат отсекает юзера сразу, поэтому за пределами окна
     * 1-го числа не исполняется ни одного count/group-by — иначе ежечасный тик
     * прогонял бы агрегаты по всем юзерам все 30 дней месяца (N+1-зона риска).
     *
     * <p>Среди прошедших окно остаются те, у кого за прошлый месяц было
     * ≥ {@link #MIN_ACTIONS} действий и которым отчёт за этот месяц ещё не слали.
     *
     * @param now момент проверки (UTC). Передаётся явно — для unit-тестов и для
     *            согласованности с {@code Clock}-бином в caller'е.
     */
    @Transactional(readOnly = true)
    public List<MonthlyReport> findDueReports(Instant now) {
        List<User> users = userRepository.findAllNotBlocked();
        if (users.isEmpty()) {
            return List.of();
        }

        List<MonthlyReport> due = new ArrayList<>();
        for (User user : users) {
            MonthlyReport report = buildReport(user, now);
            if (report != null) {
                due.add(report);
            }
        }
        return due;
    }

    /**
     * Собрать отчёт для одного юзера за прошлый месяц или вернуть {@code null},
     * если отчёт не положен (мало действий / уже отправлен).
     */
    private MonthlyReport buildReport(User user, Instant now) {
        ZoneId zone = safeZone(user.getTimezone());

        // Дешёвый TZ-фильтр ПЕРВЫМ: вне окна «1-е число + утро» агрегаты не считаем.
        if (!isFirstDayMorning(now, zone)) {
            return null;
        }

        YearMonth reportMonth = YearMonth.from(now.atZone(zone).toLocalDate()).minusMonths(1);
        int yearMonth = reportMonth.getYear() * 100 + reportMonth.getMonthValue();

        if (sentRepository.existsByUserIdAndYearMonth(user.getId(), yearMonth)) {
            return null;
        }

        // Границы месяца в TZ юзера → UTC LocalDateTime для запроса по done_at.
        LocalDateTime from = reportMonth.atDay(1).atStartOfDay(zone)
                .toInstant().atZone(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime toExclusive = reportMonth.plusMonths(1).atDay(1).atStartOfDay(zone)
                .toInstant().atZone(ZoneOffset.UTC).toLocalDateTime();

        long totalActions = careHistoryRepository.countActiveByUserIdInRange(
                user.getId(), from, toExclusive);
        if (totalActions < MIN_ACTIONS) {
            return null;
        }

        Map<TaskType, Long> breakdown = loadBreakdown(user.getId(), from, toExclusive);
        OldestPlant oldest = loadOldestPlant(user.getId(), now, zone);
        String flawlessPlant = loadFlawlessPlant(user.getId(), from, toExclusive);

        return new MonthlyReport(
                user.getId(),
                user.getTelegramChatId(),
                yearMonth,
                monthLabel(reportMonth),
                totalActions,
                breakdown,
                oldest,
                flawlessPlant
        );
    }

    private Map<TaskType, Long> loadBreakdown(Long userId, LocalDateTime from, LocalDateTime toExclusive) {
        Map<TaskType, Long> breakdown = new LinkedHashMap<>();
        for (TaskTypeCount row : careHistoryRepository.countActiveByUserIdGroupedByTaskType(
                userId, from, toExclusive)) {
            breakdown.put(row.getTaskType(), row.getCount());
        }
        return breakdown;
    }

    private OldestPlant loadOldestPlant(Long userId, Instant now, ZoneId zone) {
        List<Plant> oldest = plantRepository.findOldestLivingByUser(userId, Limit.of(1));
        if (oldest.isEmpty()) {
            return null;
        }
        Plant plant = oldest.getFirst();
        LocalDate todayLocal = now.atZone(zone).toLocalDate();
        Period age = Period.between(plant.getAcquiredAt(), todayLocal);
        return new OldestPlant(plant.getName(), age.getYears(), age.getMonths());
    }

    private String loadFlawlessPlant(Long userId, LocalDateTime from, LocalDateTime toExclusive) {
        List<PlantActionCount> flawless = careHistoryRepository.findFlawlessPlantsByUserInRange(
                userId, from, toExclusive, Limit.of(1));
        return flawless.isEmpty() ? null : flawless.getFirst().getPlantName();
    }

    /**
     * Зафиксировать факт отправки отчёта — отдельная транзакция, чтобы caller мог
     * дёрнуть её сразу после успешного Telegram send.
     *
     * <p>Insert-only через {@code INSERT ... ON CONFLICT (user_id, year_month) DO
     * NOTHING}: повторная пометка той же пары (гонка параллельных тиков, рестарт
     * пода) атомарно становится no-op на уровне БД — без SELECT-then-UPDATE и без
     * перетирания {@code sent_at} первого отчёта. Возвращаемое число вставленных
     * строк используем только для лога.
     */
    @Transactional
    public void markSent(Long userId, int yearMonth, Instant sentAt) {
        int inserted = sentRepository.insertIfAbsent(userId, yearMonth, sentAt);
        if (inserted == 0) {
            log.debug("Monthly report already marked for user={} yearMonth={}", userId, yearMonth);
        }
    }

    /**
     * DTO с готовыми данными для одного месячного отчёта. Сделан record — чтобы
     * шедулер мог использовать поля напрямую без лазания в entity, у которых уже
     * могла закрыться JPA-сессия.
     */
    public record MonthlyReport(
            Long userId,
            Long telegramChatId,
            int yearMonth,
            String monthLabel,
            long totalActions,
            Map<TaskType, Long> breakdown,
            OldestPlant oldestPlant,
            String flawlessPlantName
    ) {
        /**
         * Готовый текст отчёта на русском. Формат — retention-карточка по issue #137.
         */
        public String buildText() {
            StringBuilder sb = new StringBuilder();
            sb.append("📊 Твой отчёт за ").append(monthLabel).append("\n\n");
            sb.append("Всего действий по уходу: ").append(totalActions)
                    .append(" ").append(pluralizeActions(totalActions)).append("\n");

            if (!breakdown.isEmpty()) {
                sb.append("\nПо типам ухода:\n");
                for (TaskType type : TaskType.values()) {
                    Long count = breakdown.get(type);
                    if (count != null && count > 0) {
                        sb.append(taskTypeLabel(type)).append(": ").append(count).append("\n");
                    }
                }
            }

            if (oldestPlant != null) {
                sb.append("\n🌳 Самое старое живое растение — ").append(oldestPlant.name())
                        .append(", ").append(oldestPlant.ageText()).append(".\n");
            }

            if (flawlessPlantName != null) {
                sb.append("\n🏆 Ни разу не пропустил уход за ").append(flawlessPlantName)
                        .append(" — так держать!\n");
            }

            sb.append("\nСпасибо, что заботишься о своих растениях 🌱");
            return sb.toString();
        }

        private static String taskTypeLabel(TaskType type) {
            return switch (type) {
                case WATERING -> "💧 Полив";
                case MISTING -> "💨 Опрыскивание";
                case FERTILIZING -> "🌱 Удобрение";
                case SOIL_CHECK -> "🌍 Проверка грунта";
            };
        }
    }

    /** Самое старое живое растение и его возраст на момент отчёта. */
    public record OldestPlant(String name, int years, int months) {
        /** Человекочитаемый возраст: «N лет M месяцев» / «M месяцев». */
        public String ageText() {
            if (years <= 0) {
                int m = Math.max(months, 0);
                return m + " " + pluralizeMonths(m);
            }
            StringBuilder sb = new StringBuilder();
            sb.append(years).append(" ").append(pluralizeYears(years));
            if (months > 0) {
                sb.append(" ").append(months).append(" ").append(pluralizeMonths(months));
            }
            return sb.toString();
        }
    }

    private static String monthLabel(YearMonth ym) {
        String month = ym.getMonth().getDisplayName(TextStyle.FULL_STANDALONE, new Locale("ru"));
        return month + " " + ym.getYear();
    }

    private static String pluralizeActions(long n) {
        long mod10 = Math.abs(n) % 10;
        long mod100 = Math.abs(n) % 100;
        if (mod10 == 1 && mod100 != 11) return "действие";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "действия";
        return "действий";
    }

    private static String pluralizeYears(int n) {
        int mod10 = Math.abs(n) % 10;
        int mod100 = Math.abs(n) % 100;
        if (mod10 == 1 && mod100 != 11) return "год";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "года";
        return "лет";
    }

    private static String pluralizeMonths(int n) {
        int mod10 = Math.abs(n) % 10;
        int mod100 = Math.abs(n) % 100;
        if (mod10 == 1 && mod100 != 11) return "месяц";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "месяца";
        return "месяцев";
    }

    /**
     * Сейчас в TZ юзера 1-е число месяца И локальное время в окне
     * [{@value #MORNING_HOUR_START}:00, {@value #MORNING_HOUR_END}:00)? Окно не даёт
     * отчёту прилететь ночью юзеру в дальнем восточном поясе и ограничивает рассылку
     * первым днём месяца.
     */
    private static boolean isFirstDayMorning(Instant now, ZoneId zone) {
        var local = now.atZone(zone);
        LocalTime localTime = local.toLocalTime();
        return local.getDayOfMonth() == 1
                && !localTime.isBefore(LocalTime.of(MORNING_HOUR_START, 0))
                && localTime.isBefore(LocalTime.of(MORNING_HOUR_END, 0));
    }

    private static ZoneId safeZone(String tz) {
        if (tz == null || tz.isBlank()) return ZoneOffset.UTC;
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            return ZoneOffset.UTC;
        }
    }
}

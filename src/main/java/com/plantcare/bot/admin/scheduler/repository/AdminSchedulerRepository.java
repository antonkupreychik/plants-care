package com.plantcare.bot.admin.scheduler.repository;

import com.plantcare.bot.repository.CareScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Запросы для /admin/scheduler (issue #59). Простые агрегаты — выносим в отдельный
 * репо, чтобы NotificationLogRepository остался узко-сфокусированным на runtime-нуждах
 * шедулера, а агрегации админки жили рядом с админ-контроллером.
 */
@Repository
@RequiredArgsConstructor
public class AdminSchedulerRepository {

    private final JdbcTemplate jdbcTemplate;
    private final CareScheduleRepository careScheduleRepository;

    /**
     * Сколько расписаний сейчас due — это число попадёт в очередь следующего тика.
     * Переиспользуем существующий {@code findDueSchedules} вместо дубликата SQL,
     * чтобы фильтры (active, archived, blocked) автоматически совпадали с
     * реальной логикой шедулера.
     */
    public long countSchedulesInQueue(LocalDateTime now) {
        return careScheduleRepository.findDueSchedules(now).size();
    }

    /** Сколько уведомлений ушло за последние 24 часа (по {@code notifications_log.sent_at}). */
    public long countSentSince(LocalDateTime since) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications_log WHERE sent_at >= ?",
                Long.class,
                since
        );
        return total == null ? 0 : total;
    }

    /**
     * Гистограмма отправок по часам за последние 24 часа в указанной TZ.
     *
     * <p>{@code sent_at} в БД — naive TIMESTAMP, хранится в UTC. SQL-выражение
     * {@code (sent_at AT TIME ZONE 'UTC') AT TIME ZONE :tz} интерпретирует его
     * как UTC и переводит в TZ админа, чтобы час в графике совпадал с тем, что
     * админ видит на часах у себя на стене.
     *
     * @return массив из 24 элементов: ключ — час (0..23 в указанной TZ),
     *         значение — сколько отправок. Часы без отправок не возвращаются БД, но
     *         попадают в map с {@code 0}.
     */
    public Map<Integer, Long> sentByHourSince(LocalDateTime since, String tzId) {
        Map<Integer, Long> result = new HashMap<>();
        for (int h = 0; h < 24; h++) {
            result.put(h, 0L);
        }
        // GROUP BY 1 (по позиции в SELECT) — потому что Postgres не считает
        // два одинаковых `EXTRACT(... AT TIME ZONE ?)` идентичными выражениями
        // в SELECT и GROUP BY (параметр для планировщика — «чёрный ящик»).
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT EXTRACT(HOUR FROM (sent_at AT TIME ZONE 'UTC') AT TIME ZONE ?) AS hour,
                       COUNT(*) AS cnt
                FROM notifications_log
                WHERE sent_at >= ?
                GROUP BY 1
                """, tzId, since);
        for (Map<String, Object> row : rows) {
            int hour = ((Number) row.get("hour")).intValue();
            long cnt = ((Number) row.get("cnt")).longValue();
            result.put(hour, cnt);
        }
        return result;
    }

    /**
     * Удобный helper для шаблона: 24 элемента в хронологическом порядке от старейшего
     * к новейшему относительно текущего часа (в той TZ, в которой группируем данные).
     *
     * <p>{@code currentHour} передаётся снаружи, потому что bucketing
     * выполняется в админской TZ (см. {@link #sentByHourSince}), и «текущий час»
     * тоже должен быть в этой TZ — иначе таймлайн сдвинется на разницу зон.
     */
    public List<int[]> hourlyTimeline(int currentHour, Map<Integer, Long> sentByHour) {
        List<int[]> timeline = new ArrayList<>(24);
        for (int offset = 23; offset >= 0; offset--) {
            int hour = ((currentHour - offset) % 24 + 24) % 24;
            int count = sentByHour.getOrDefault(hour, 0L).intValue();
            timeline.add(new int[]{hour, count});
        }
        return timeline;
    }
}

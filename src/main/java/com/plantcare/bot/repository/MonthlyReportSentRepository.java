package com.plantcare.bot.repository;

import com.plantcare.bot.domain.MonthlyReportSent;
import com.plantcare.bot.domain.MonthlyReportSentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Идемпотентность месячных отчётов (issue #137).
 * Маркер «(user_id, year_month) уже отправлен» — наличие записи в таблице.
 */
@Repository
public interface MonthlyReportSentRepository
        extends JpaRepository<MonthlyReportSent, MonthlyReportSentId> {

    boolean existsByUserIdAndYearMonth(Long userId, Integer yearMonth);

    /**
     * Insert-only пометка «отчёт отправлен». Атомарный {@code ON CONFLICT DO NOTHING}
     * по PK {@code (user_id, year_month)}: повторная пометка той же пары (гонка
     * параллельных тиков, рестарт пода) — no-op, без SELECT-then-UPDATE и без
     * перетирания {@code sent_at} первого отчёта. Возвращает число вставленных строк
     * (1 — вставили, 0 — запись уже была).
     *
     * <p>Через JPA-save с присвоенным {@code @IdClass}-id Hibernate сделал бы merge
     * (SELECT, затем UPDATE), а не INSERT — поэтому идемпотентность реализована
     * нативным запросом, а не {@code save()}.
     */
    @Modifying
    @Query(value = """
            INSERT INTO monthly_report_sent (user_id, year_month, sent_at)
            VALUES (:userId, :yearMonth, :sentAt)
            ON CONFLICT (user_id, year_month) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId,
                       @Param("yearMonth") Integer yearMonth,
                       @Param("sentAt") Instant sentAt);
}

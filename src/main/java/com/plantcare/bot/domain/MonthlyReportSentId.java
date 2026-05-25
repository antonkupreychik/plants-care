package com.plantcare.bot.domain;

import java.io.Serializable;
import java.util.Objects;

/**
 * Композитный PK для {@link MonthlyReportSent} (issue #137).
 * Имена и типы полей должны точно соответствовать @Id-полям сущности.
 */
public class MonthlyReportSentId implements Serializable {

    private Long userId;
    private Integer yearMonth;

    public MonthlyReportSentId() {
    }

    public MonthlyReportSentId(Long userId, Integer yearMonth) {
        this.userId = userId;
        this.yearMonth = yearMonth;
    }

    public Long getUserId() {
        return userId;
    }

    public Integer getYearMonth() {
        return yearMonth;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MonthlyReportSentId that)) return false;
        return Objects.equals(userId, that.userId)
                && Objects.equals(yearMonth, that.yearMonth);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, yearMonth);
    }
}

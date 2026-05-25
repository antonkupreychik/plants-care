package com.plantcare.bot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Запись «месячный отчёт уже отправлен этому юзеру за этот месяц» (issue #137).
 * Используется для идемпотентности шедулера месячных отчётов: перед отправкой
 * шедулер пытается INSERT в эту таблицу; первичный ключ {@code (user_id, year_month)}
 * гарантирует, что за один и тот же отчётный месяц (по календарю TZ юзера) не уйдёт
 * второй отчёт — независимо от повторных тиков, рестарта пода и rolling deploy.
 *
 * <p>{@code year_month} — это {@code YYYYMM} прошлого месяца в TZ юзера
 * (например 202604 для отчёта за апрель 2026).
 *
 * <p>FK на {@link User} c {@code ON DELETE CASCADE} (см. миграцию V23):
 * при удалении пользователя история отправленных отчётов уезжает вместе с ним.
 */
@Entity
@Table(name = "monthly_report_sent")
@IdClass(MonthlyReportSentId.class)
@Getter
@Setter
@NoArgsConstructor
public class MonthlyReportSent {

    public MonthlyReportSent(Long userId, Integer yearMonth, Instant sentAt) {
        this.userId = userId;
        this.yearMonth = yearMonth;
        this.sentAt = sentAt;
    }

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "year_month", nullable = false)
    private Integer yearMonth;

    /**
     * Пользователь, на которого ссылается запись. LAZY — для вставки-проверки
     * сама ассоциация не нужна, нужен только userId. Маппится
     * {@code insertable=false/updatable=false}, чтобы PK-колонка писалась через
     * поле {@link #userId}, а не через эту связь.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;
}

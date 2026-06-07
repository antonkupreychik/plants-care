package com.plantcare.bot.service;

import com.plantcare.core.service.MonthlyReportService;

import com.plantcare.core.observability.SentryTags;
import com.plantcare.core.observability.SentryTags.Layer;
import com.plantcare.bot.telegram.RateLimitedTelegramSender;
import com.plantcare.bot.telegram.SendCallbacks;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Hourly-шедулер месячных отчётов по уходу (issue #137).
 *
 * <p>Каждый час дёргает {@link MonthlyReportService#findDueReports}, который сам
 * отбирает юзеров, у кого в TZ юзера сейчас наступило 1-е число нового месяца И
 * идёт утреннее окно (09:00–10:00 локально), и для каждого готовит
 * retention-карточку с итогами за прошлый месяц.
 *
 * <p>Фильтр «1-е число + утро» живёт в сервисе (перед тяжёлыми агрегатами по
 * {@code care_history}), а не здесь — иначе ежечасный тик гонял бы агрегаты по
 * всем юзерам все 30 дней месяца. Шедулер лишь отправляет уже отобранных
 * кандидатов. Идемпотентность — на уровне БД ({@code monthly_report_sent}, PK
 * {@code (user_id, year_month)}): второй отчёт за тот же отчётный месяц невозможен
 * независимо от числа тиков.
 *
 * <p>Telegram-вызовы намеренно вне транзакции к БД: сначала read-only выборка
 * кандидатов с расчётом метрик, затем отправка, затем отдельная транзакция на
 * пометку «отправлено».
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyReportScheduler {

    private final MonthlyReportService monthlyReportService;
    private final RateLimitedTelegramSender telegramSender;
    private final Clock clock;

    /**
     * Cron в UTC: каждый час в 15-ю минуту. Смещение 15 минут — чтобы не толкаться
     * ни с PlantAnniversaryScheduler ('0 5 * * * *'), ни с hourly fixedRate-задачами,
     * стартующими в 0-ю минуту.
     */
    // Issue #279: hourly-задача — lockAtMostFor 90 мин, lockAtLeastFor 55 мин.
    @Scheduled(cron = "0 15 * * * *", zone = "UTC")
    @SchedulerLock(name = "MonthlyReportScheduler_tick",
            lockAtMostFor = "PT90M", lockAtLeastFor = "PT55M")
    public void tick() {
        // Issue #114: изолированный scope на тик — captureException внутри получит
        // тег layer=scheduler, и он не утечёт на соседние задачи shared-потока.
        SentryTags.runWithLayer(Layer.SCHEDULER, "MonthlyReportScheduler", () -> {
            Instant now = clock.instant();
            List<MonthlyReportService.MonthlyReport> due =
                    monthlyReportService.findDueReports(now);

            if (due.isEmpty()) {
                return;
            }
            log.info("Monthly report tick: {} candidates found", due.size());

            for (MonthlyReportService.MonthlyReport report : due) {
                try {
                    enqueueReport(report, now);
                } catch (Exception e) {
                    log.error("Failed to handle monthly report for user {}: {}",
                            report.userId(), e.getMessage(), e);
                    Sentry.captureException(e);
                }
            }
        });
    }

    /**
     * Issue #29: отправка ушла в rate-limited очередь. Пометка «отправлено»
     * ({@code markSent} — идемпотентный PK-based upsert) переехала в onSuccess-колбэк
     * на воркер-потоке. Захватываем только примитивы из снимка report, чтобы не
     * тащить состояние между потоками.
     */
    private void enqueueReport(MonthlyReportService.MonthlyReport report, Instant now) {
        SendMessage message = SendMessage.builder()
                .chatId(report.telegramChatId().toString())
                .text(report.buildText())
                .build();

        final Long userId = report.userId();
        final int yearMonth = report.yearMonth();
        final long totalActions = report.totalActions();
        telegramSender.enqueue(message, new SendCallbacks(
                () -> {
                    monthlyReportService.markSent(userId, yearMonth, now);
                    log.info("Monthly report sent: user={} yearMonth={} actions={}",
                            userId, yearMonth, totalActions);
                },
                null));
    }
}

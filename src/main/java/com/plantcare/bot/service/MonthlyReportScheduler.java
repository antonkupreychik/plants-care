package com.plantcare.bot.service;

import com.plantcare.bot.client.TelegramClientProvider;
import com.plantcare.bot.observability.SentryTags;
import com.plantcare.bot.observability.SentryTags.Layer;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

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
    private final TelegramClientProvider telegramClientProvider;
    private final Clock clock;

    /**
     * Cron в UTC: каждый час в 15-ю минуту. Смещение 15 минут — чтобы не толкаться
     * ни с PlantAnniversaryScheduler ('0 5 * * * *'), ни с hourly fixedRate-задачами,
     * стартующими в 0-ю минуту.
     */
    @Scheduled(cron = "0 15 * * * *", zone = "UTC")
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
                    if (sendReport(report)) {
                        monthlyReportService.markSent(report.userId(), report.yearMonth(), now);
                        log.info("Monthly report sent: user={} yearMonth={} actions={}",
                                report.userId(), report.yearMonth(), report.totalActions());
                    }
                } catch (Exception e) {
                    log.error("Failed to handle monthly report for user {}: {}",
                            report.userId(), e.getMessage(), e);
                    Sentry.captureException(e);
                }
            }
        });
    }

    private boolean sendReport(MonthlyReportService.MonthlyReport report) {
        SendMessage message = SendMessage.builder()
                .chatId(report.telegramChatId().toString())
                .text(report.buildText())
                .build();
        try {
            telegramClientProvider.getTelegramClient().execute(message);
            return true;
        } catch (TelegramApiException e) {
            log.error("Failed to send monthly report (user={}, chat={}): {}",
                    report.userId(), report.telegramChatId(), e.getMessage());
            return false;
        }
    }
}

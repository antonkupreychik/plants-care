package com.plantcare.bot.service;

import com.plantcare.bot.client.TelegramClientProvider;
import com.plantcare.bot.service.MonthlyReportService.MonthlyReport;
import com.plantcare.bot.service.MonthlyReportService.OldestPlant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link MonthlyReportScheduler} (issue #137) с фиксированным {@link Clock}.
 * Внешний мир (Telegram) замокан; сервис — мок, чтобы изолировать оркестрацию
 * шедулера: отправку отобранных кандидатов и порядок «send → markSent».
 *
 * <p>Окно «1-е число + утро 09:00–10:00 в TZ юзера» здесь НЕ проверяется — оно
 * переехало внутрь {@link MonthlyReportService#findDueReports} (перед агрегатами),
 * и покрывается {@code MonthlyReportServiceTest} на Testcontainers. Шедулер
 * отправляет всё, что вернул {@code findDueReports}, и сам окно не считает.
 */
@DisplayName("MonthlyReportScheduler — оркестрация отправки (issue #137)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonthlyReportSchedulerTest {

    @Mock private MonthlyReportService monthlyReportService;
    @Mock private TelegramClientProvider telegramClientProvider;
    @Mock private TelegramClient telegramClient;

    private MonthlyReportScheduler newScheduler(Clock clock) {
        when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);
        return new MonthlyReportScheduler(
                monthlyReportService, telegramClientProvider, clock);
    }

    @Test
    @DisplayName("should_send_and_mark_when_report_is_due")
    void should_send_and_mark_when_report_is_due() throws TelegramApiException {
        // arrange: сервис уже отобрал кандидата (окно посчитано внутри сервиса).
        Instant now = Instant.parse("2026-05-01T06:30:00Z");
        MonthlyReportScheduler scheduler = newScheduler(fixed(now));

        MonthlyReport report = report(1L, 555L, 202604);
        when(monthlyReportService.findDueReports(now)).thenReturn(List.of(report));

        // act
        scheduler.tick();

        // assert
        verify(telegramClient, times(1)).execute(any(SendMessage.class));
        verify(monthlyReportService).markSent(eq(1L), eq(202604), eq(now));
    }

    @Test
    @DisplayName("should_not_mark_when_telegram_send_fails")
    void should_not_mark_when_telegram_send_fails() throws TelegramApiException {
        // arrange: кандидат есть, но Telegram падает → markSent НЕ зовём.
        Instant now = Instant.parse("2026-05-01T06:30:00Z");
        MonthlyReportScheduler scheduler = newScheduler(fixed(now));

        when(monthlyReportService.findDueReports(now))
                .thenReturn(List.of(report(1L, 555L, 202604)));
        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiException("boom"));

        // act
        scheduler.tick();

        // assert
        verify(telegramClient, times(1)).execute(any(SendMessage.class));
        verify(monthlyReportService, never()).markSent(anyLong(), anyInt(), any(Instant.class));
    }

    @Test
    @DisplayName("should_do_nothing_when_no_due_reports")
    void should_do_nothing_when_no_due_reports() throws TelegramApiException {
        // arrange
        Instant now = Instant.parse("2026-05-01T06:30:00Z");
        MonthlyReportScheduler scheduler = newScheduler(fixed(now));
        when(monthlyReportService.findDueReports(now)).thenReturn(List.of());

        // act
        scheduler.tick();

        // assert
        verify(telegramClient, never()).execute(any(SendMessage.class));
        verify(monthlyReportService, never()).markSent(anyLong(), anyInt(), any(Instant.class));
    }

    @Test
    @DisplayName("should_continue_when_telegram_fails_for_one_user")
    void should_continue_when_telegram_fails_for_one_user() throws TelegramApiException {
        // arrange: два кандидата, у первого Telegram падает — второй всё равно
        // обрабатывается и помечается.
        Instant now = Instant.parse("2026-05-01T06:30:00Z");
        MonthlyReportScheduler scheduler = newScheduler(fixed(now));

        when(monthlyReportService.findDueReports(now)).thenReturn(List.of(
                report(1L, 555L, 202604),
                report(2L, 666L, 202604)));
        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiException("boom"))
                .thenReturn(null);

        // act
        scheduler.tick();

        // assert
        verify(telegramClient, times(2)).execute(any(SendMessage.class));
        verify(monthlyReportService, never()).markSent(eq(1L), anyInt(), any(Instant.class));
        verify(monthlyReportService).markSent(eq(2L), eq(202604), eq(now));
    }

    // ===================== helpers =====================

    private static Clock fixed(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static MonthlyReport report(Long userId, Long chatId, int yearMonth) {
        return new MonthlyReport(
                userId,
                chatId,
                yearMonth,
                "апрель 2026",
                5L,
                Map.of(),
                new OldestPlant("Фикус", 1, 2),
                "Фикус"
        );
    }
}

package com.plantcare.bot.service;

import com.plantcare.bot.service.MonthlyReportService.MonthlyReport;
import com.plantcare.bot.service.MonthlyReportService.OldestPlant;
import com.plantcare.bot.telegram.RateLimitedTelegramSender;
import com.plantcare.bot.telegram.SendCallbacks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link MonthlyReportScheduler} (issue #137) после перевода на
 * rate-limited очередь (issue #29). Шедулер кладёт отчёт в
 * {@link RateLimitedTelegramSender#enqueue}; пометка {@code markSent} переехала
 * в onSuccess-колбэк. Тесты проверяют постановку в очередь и маршрутизацию
 * пометки через захваченные {@link SendCallbacks}.
 *
 * <p>Окно «1-е число + утро» считается внутри {@link MonthlyReportService#findDueReports}
 * и покрывается отдельным Testcontainers-тестом — шедулер отправляет всё, что вернул сервис.
 */
@DisplayName("MonthlyReportScheduler — оркестрация отправки (issue #137 / #29)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonthlyReportSchedulerTest {

    @Mock private MonthlyReportService monthlyReportService;
    @Mock private RateLimitedTelegramSender telegramSender;

    private MonthlyReportScheduler newScheduler(Clock clock) {
        return new MonthlyReportScheduler(monthlyReportService, telegramSender, clock);
    }

    @Test
    @DisplayName("should_enqueue_and_mark_on_success_when_report_is_due")
    void should_enqueue_and_mark_on_success_when_report_is_due() {
        // arrange: сервис уже отобрал кандидата (окно посчитано внутри сервиса).
        Instant now = Instant.parse("2026-05-01T06:30:00Z");
        MonthlyReportScheduler scheduler = newScheduler(fixed(now));

        MonthlyReport report = report(1L, 555L, 202604);
        when(monthlyReportService.findDueReports(now)).thenReturn(List.of(report));

        // act
        scheduler.tick();

        // assert: сообщение поставлено в очередь, markSent ещё НЕ вызван (только в колбэке).
        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        ArgumentCaptor<SendCallbacks> callbacksCaptor = ArgumentCaptor.forClass(SendCallbacks.class);
        verify(telegramSender, times(1)).enqueue(messageCaptor.capture(), callbacksCaptor.capture());
        assertThat(messageCaptor.getValue().getChatId()).isEqualTo("555");
        verify(monthlyReportService, never()).markSent(anyLong(), anyInt(), any(Instant.class));

        runSuccess(callbacksCaptor.getValue());

        verify(monthlyReportService).markSent(eq(1L), eq(202604), eq(now));
    }

    @Test
    @DisplayName("should_not_mark_when_send_fails_callback")
    void should_not_mark_when_send_fails_callback() {
        // arrange: кандидат есть; отправка проваливается → markSent НЕ зовём.
        Instant now = Instant.parse("2026-05-01T06:30:00Z");
        MonthlyReportScheduler scheduler = newScheduler(fixed(now));
        when(monthlyReportService.findDueReports(now))
                .thenReturn(List.of(report(1L, 555L, 202604)));

        scheduler.tick();

        ArgumentCaptor<SendCallbacks> callbacksCaptor = ArgumentCaptor.forClass(SendCallbacks.class);
        verify(telegramSender).enqueue(any(SendMessage.class), callbacksCaptor.capture());

        // onFailure=null для месячного отчёта — провал не должен ни ронять, ни помечать.
        runFailure(callbacksCaptor.getValue(),
                new org.telegram.telegrambots.meta.exceptions.TelegramApiException("boom"));

        verify(monthlyReportService, never()).markSent(anyLong(), anyInt(), any(Instant.class));
    }

    @Test
    @DisplayName("should_do_nothing_when_no_due_reports")
    void should_do_nothing_when_no_due_reports() {
        // arrange
        Instant now = Instant.parse("2026-05-01T06:30:00Z");
        MonthlyReportScheduler scheduler = newScheduler(fixed(now));
        when(monthlyReportService.findDueReports(now)).thenReturn(List.of());

        // act
        scheduler.tick();

        // assert
        verifyNoInteractions(telegramSender);
        verify(monthlyReportService, never()).markSent(anyLong(), anyInt(), any(Instant.class));
    }

    @Test
    @DisplayName("should_enqueue_every_candidate_and_mark_each_independently")
    void should_enqueue_every_candidate_and_mark_each_independently() {
        // arrange: два кандидата — каждый ставится в очередь, каждый помечается своим колбэком.
        Instant now = Instant.parse("2026-05-01T06:30:00Z");
        MonthlyReportScheduler scheduler = newScheduler(fixed(now));
        when(monthlyReportService.findDueReports(now)).thenReturn(List.of(
                report(1L, 555L, 202604),
                report(2L, 666L, 202604)));

        // act
        scheduler.tick();

        // assert
        ArgumentCaptor<SendCallbacks> callbacksCaptor = ArgumentCaptor.forClass(SendCallbacks.class);
        verify(telegramSender, times(2)).enqueue(any(SendMessage.class), callbacksCaptor.capture());

        List<SendCallbacks> allCallbacks = callbacksCaptor.getAllValues();
        runSuccess(allCallbacks.get(0));
        runSuccess(allCallbacks.get(1));

        verify(monthlyReportService).markSent(eq(1L), eq(202604), eq(now));
        verify(monthlyReportService).markSent(eq(2L), eq(202604), eq(now));
    }

    // ===================== helpers =====================

    /** Эмулирует вызов onSuccess воркером очереди (с null-guard, как в проде). */
    private static void runSuccess(SendCallbacks callbacks) {
        if (callbacks.onSuccess() != null) {
            callbacks.onSuccess().run();
        }
    }

    /** Эмулирует вызов onFailure воркером очереди (с null-guard, как в проде). */
    private static void runFailure(SendCallbacks callbacks,
                                   org.telegram.telegrambots.meta.exceptions.TelegramApiException e) {
        if (callbacks.onFailure() != null) {
            callbacks.onFailure().accept(e);
        }
    }

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

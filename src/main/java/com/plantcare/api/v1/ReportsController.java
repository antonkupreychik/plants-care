package com.plantcare.api.v1;

import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.generated.ReportsApi;
import com.plantcare.api.generated.model.MonthlyReportResponse;
import com.plantcare.api.generated.model.WeeklyHealthBucket;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.service.MonthlyReportApiService;
import com.plantcare.core.service.MonthlyReportApiService.MonthlyReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API сводных отчётов по уходу (issue #192, gap G23).
 *
 * <p>Документация и mapping живут в сгенерированном {@link ReportsApi}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ReportsController implements ReportsApi {

    private final MonthlyReportApiService monthlyReportApiService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public MonthlyReportResponse getMonthlyReport(String month) {
        User user = currentUserProvider.currentUser();
        log.info("GET /api/v1/reports/monthly: userId={}, month={}", user.getId(), month);

        MonthlyReport report = monthlyReportApiService.getMonthlyReport(
                user.getId(), user.getTimezone(), month);

        return toResponse(report);
    }

    private static MonthlyReportResponse toResponse(MonthlyReport report) {
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (TaskType type : TaskType.values()) {
            byType.put(type.name(), Math.toIntExact(report.byType().getOrDefault(type, 0L)));
        }

        List<WeeklyHealthBucket> healthTrend = report.healthTrend().stream()
                .map(b -> new WeeklyHealthBucket(b.week(), Math.toIntExact(b.done()), b.onTimePct()))
                .toList();

        return new MonthlyReportResponse(
                report.month(),
                Math.toIntExact(report.done()),
                Math.toIntExact(report.overdue()),
                byType,
                report.streak(),
                healthTrend
        );
    }
}

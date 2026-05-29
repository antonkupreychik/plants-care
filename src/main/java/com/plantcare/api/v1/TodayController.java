package com.plantcare.api.v1;

import com.plantcare.api.generated.TodayApi;
import com.plantcare.api.generated.model.TaskDto;
import com.plantcare.api.generated.model.TodayResponse;
import com.plantcare.api.generated.model.TodaySummary;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.TodayApiService;
import com.plantcare.core.service.TodayApiService.TodayTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * REST API для получения задач ухода на сегодня (issue #86, #184).
 *
 * <p>Документация и mapping живут в сгенерированном {@link TodayApi}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class TodayController implements TodayApi {

    private final TodayApiService todayApiService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public TodayResponse getToday() {
        User user = currentUserProvider.currentUser();
        log.info("GET /api/v1/today: userId={}, timezone={}", user.getId(), user.getTimezone());

        List<TodayTask> todayTasks = todayApiService.getTodayTasks(user.getId(), user.getTimezone());
        LocalDateTime now = todayApiService.nowUtc();

        List<TaskDto> tasks = todayTasks.stream()
                .map(t -> toTaskDto(t.schedule(), t.doneAt()))
                .toList();

        int total = tasks.size();
        int done = (int) todayTasks.stream().filter(t -> t.doneAt() != null).count();
        int overdue = (int) todayTasks.stream()
                .filter(t -> t.doneAt() == null && t.schedule().getNextDueAt().isBefore(now))
                .count();
        int remaining = total - done;

        TodaySummary summary = new TodaySummary(total, done, remaining, overdue);
        return new TodayResponse(tasks, total, summary);
    }

    /**
     * Маппинг расписания → DTO задачи без отметки «сделано». Используется из
     * {@link CalendarController}, поэтому вынесен в общедоступный helper.
     */
    static TaskDto toTaskDto(CareSchedule schedule) {
        return toTaskDto(schedule, null);
    }

    /**
     * Маппинг расписания → DTO задачи с опциональным моментом отметки «сделано»
     * ({@code doneAt}, issue #184). {@code null} — pending.
     */
    static TaskDto toTaskDto(CareSchedule schedule, LocalDateTime doneAt) {
        TaskDto dto = new TaskDto(
                schedule.getId(),
                schedule.getPlant().getId(),
                schedule.getPlant().getName(),
                schedule.getTaskType().name(),
                schedule.getNextDueAt().atOffset(ZoneOffset.UTC)
        );
        if (schedule.getPlant().getLocation() != null) {
            dto.locationName(schedule.getPlant().getLocation().getName());
        }
        if (schedule.getPlant().getSpecies() != null) {
            dto.speciesId(schedule.getPlant().getSpecies().getId());
            dto.speciesName(schedule.getPlant().getSpecies().getName());
        }
        if (doneAt != null) {
            dto.doneAt(doneAt.atOffset(ZoneOffset.UTC));
        }
        return dto;
    }
}

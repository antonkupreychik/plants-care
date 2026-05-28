package com.plantcare.api.v1;

import com.plantcare.api.generated.TodayApi;
import com.plantcare.api.generated.model.TaskDto;
import com.plantcare.api.generated.model.TodayResponse;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.TodayApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.util.List;

/**
 * REST API для получения задач ухода на сегодня (issue #86).
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

        List<CareSchedule> schedules = todayApiService.getTodaySchedules(user.getId(), user.getTimezone());

        List<TaskDto> tasks = schedules.stream()
                .map(TodayController::toTaskDto)
                .toList();

        return new TodayResponse(tasks, tasks.size());
    }

    /**
     * Маппинг расписания → DTO задачи. Используется также из
     * {@link CalendarController}, поэтому вынесен в общедоступный helper.
     */
    static TaskDto toTaskDto(CareSchedule schedule) {
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
        return dto;
    }
}

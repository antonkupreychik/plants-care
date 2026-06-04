package com.plantcare.api.v1;

import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.generated.VacationApi;
import com.plantcare.api.generated.model.VacationRequest;
import com.plantcare.api.generated.model.VacationResponse;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * REST API режима отпуска (issue #189, mobile G21, экран 25).
 *
 * <p>Документация и mapping живут в сгенерированном {@link VacationApi}
 * (см. openapi/resources/vacation.yaml). Контроллер тонкий: делегирование
 * {@link UserService} и формирование ответа.
 *
 * <p>Поведение дедлайнов: расписания НЕ переносятся. Просроченные задачи
 * накапливаются и отображаются в welcome-back сводке через
 * {@code VacationSchedulerService} после окончания отпуска.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class VacationController implements VacationApi {

    private final UserService userService;
    private final CurrentUserProvider currentUserProvider;
    private final Clock clock;

    @Override
    public VacationResponse getVacation() {
        User user = currentUserProvider.currentUser();
        log.debug("GET /api/v1/vacation: userId={}", user.getId());
        return toResponse(user);
    }

    @Override
    public VacationResponse startVacation(VacationRequest vacationRequest) {
        User user = currentUserProvider.currentUser();
        log.info("POST /api/v1/vacation: userId={}, from={}, to={}",
                user.getId(), vacationRequest.getFrom(), vacationRequest.getTo());

        User updated = userService.startVacationByDateRange(
                user, vacationRequest.getFrom(), vacationRequest.getTo());
        return toResponse(updated);
    }

    @Override
    public void endVacation() {
        User user = currentUserProvider.currentUser();
        log.info("DELETE /api/v1/vacation: userId={}", user.getId());
        userService.endVacation(user);
    }

    private VacationResponse toResponse(User user) {
        LocalDateTime pausedUntil = user.getPausedUntil();
        LocalDateTime now = LocalDateTime.now(clock);
        boolean active = pausedUntil != null && pausedUntil.isAfter(now);

        VacationResponse response = new VacationResponse(active);
        if (active) {
            response.pausedUntil(pausedUntil.atOffset(ZoneOffset.UTC));
        }
        return response;
    }
}

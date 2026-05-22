package com.plantcare.bot.controller.api.v1;

import com.plantcare.bot.controller.api.UserApiResolver;
import com.plantcare.bot.controller.api.v1.dto.CareEventResponse;
import com.plantcare.bot.controller.api.v1.dto.CreateCareEventRequest;
import com.plantcare.bot.domain.CareHistory;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.service.CareEventApiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API для регистрации и отмены событий ухода за растениями (issue #86).
 *
 * <p>Идентификация пользователя — через заголовок {@code X-Chat-Id}
 * (Telegram chat id). Авторизация по токену придёт позже.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/care-events")
@RequiredArgsConstructor
public class CareEventController {

    private final CareEventApiService careEventApiService;
    private final UserApiResolver userApiResolver;

    /**
     * POST /api/v1/care-events — регистрация события ухода.
     *
     * <p>Если запрос содержит {@code clientId}, который уже встречался ранее,
     * возвращает существующую запись без повторной записи в БД (идемпотентность).
     *
     * @return 201 Created с телом {@link CareEventResponse}
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CareEventResponse create(
            @RequestBody @Valid CreateCareEventRequest req,
            @RequestHeader("X-Chat-Id") Long chatId
    ) {
        User user = userApiResolver.resolve(chatId);
        log.info("POST /api/v1/care-events: userId={}, plantId={}, type={}, clientId={}",
                user.getId(), req.plantId(), req.type(), req.clientId());

        CareHistory history = careEventApiService.registerEvent(
                user.getId(),
                req.plantId(),
                req.type().toTaskType(),
                req.performedAt(),
                req.note(),
                req.clientId()
        );

        return CareEventResponse.from(history);
    }

    /**
     * DELETE /api/v1/care-events/{id} — отмена события ухода (compensation).
     *
     * <p>Запись не удаляется физически — создаётся компенсирующая запись
     * и проставляется FK {@code cancelled_by}.
     *
     * @return 204 No Content
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(
            @PathVariable Long id,
            @RequestHeader("X-Chat-Id") Long chatId
    ) {
        User user = userApiResolver.resolve(chatId);
        log.info("DELETE /api/v1/care-events/{}: userId={}", id, user.getId());

        careEventApiService.cancelEvent(user.getId(), id);
    }
}

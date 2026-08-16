package com.plantcare.api.errorlog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Тестовый контроллер под AC issue #97 «бросить exception в API → появилась запись».
 *
 * <p>Живёт в {@code src/test} и подключается точечным {@code @Import} — в прод-коде
 * эндпоинта, который гарантированно падает, быть не должно.
 *
 * <p>Пакет {@code com.plantcare.api.*} выбран намеренно: только он попадает под
 * {@code @RestControllerAdvice(basePackages = "com.plantcare.api")}, а именно его
 * {@code handleRuntime} и делает {@code log.error} — тот самый лог, который обязан
 * оказаться в {@code error_logs}.
 */
@RestController
public class ErrorLogBoomController {

    static final String PATH = "/api/v1/test-only/boom";

    @GetMapping(PATH)
    public String boom() {
        throw new UnsupportedOperationException("boom from test controller");
    }
}

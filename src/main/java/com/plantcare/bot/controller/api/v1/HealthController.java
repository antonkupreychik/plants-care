package com.plantcare.bot.controller.api.v1;

import com.plantcare.bot.api.generated.HealthApi;
import com.plantcare.bot.api.generated.model.HealthResponse;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness probe REST API. Возвращает фиксированный статус без проверок зависимостей.
 *
 * <p>Документация и mapping живут в сгенерированном {@link HealthApi} (см. openapi.yaml).
 */
@RestController
public class HealthController implements HealthApi {

    @Override
    public HealthResponse getHealth() {
        return new HealthResponse("UP");
    }
}

package com.plantcare.api.v1;

import com.plantcare.api.generated.HealthApi;
import com.plantcare.api.generated.model.HealthResponse;
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

package com.plantcare.bot.controller.api.v1;

import com.plantcare.bot.controller.api.v1.dto.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness probe REST API. Возвращает фиксированный статус без проверок зависимостей.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @GetMapping
    public HealthResponse health() {
        return new HealthResponse("UP");
    }
}

package com.plantcare.api;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stub-контроллер для теста {@link ApiExceptionHandler}.
 *
 * <p>Лежит в пакете {@code com.plantcare.api}, поэтому
 * {@code @RestControllerAdvice(basePackages = "com.plantcare.api")}
 * захватывает его исключения. Если из advice удалить basePackages — поведение
 * advice не изменится (он по-прежнему ловит этот контроллер), но также начнёт
 * ловить контроллеры вне API-пакета; такой регресс должен ловиться отдельным
 * интеграционным тестом на admin-слое (см. отчёт).
 */
@RestController
@RequestMapping("/test-stub")
class ApiExceptionHandlerTestStubController {

    @PostMapping("/validation")
    public void validation(@Valid @RequestBody DummyBody body) {
        // body validated by @Valid → MethodArgumentNotValidException при пустом name
    }

    @GetMapping("/not-found")
    public void notFound() {
        throw new EntityNotFoundException("plant 42 not found");
    }

    @GetMapping("/access-denied")
    public void accessDenied() {
        throw new AccessDeniedException("nope");
    }

    @GetMapping("/runtime")
    public void runtime() {
        throw new RuntimeException("boom");
    }

    record DummyBody(@NotBlank String name) {
    }
}

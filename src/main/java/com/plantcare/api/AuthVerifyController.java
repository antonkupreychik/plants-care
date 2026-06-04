package com.plantcare.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Issue #215: веб-фоллбэк для magic-link на десктопе / при отсутствии приложения.
 *
 * <p>Когда пользователь открывает magic-link ({@code /auth/verify?token=...}) на компьютере
 * или на телефоне без установленного приложения, он попадает на эту страницу.
 * Она объясняет, что нужно открыть ссылку на телефоне с установленным приложением.
 *
 * <p>Токен из query НЕ отображается в UI и не обрабатывается бэкендом — верификацию
 * выполняет мобильное приложение напрямую через {@code /api/v1/auth/magic-link/verify}.
 *
 * <p>Путь вне {@code /api/v1/**} → попадает в дефолтную permitAll-цепочку,
 * авторизация не нужна.
 */
@Controller
public class AuthVerifyController {

    /**
     * Фоллбэк-страница для magic-link.
     *
     * <p>Работает без query-параметров (они необязательны), так что
     * {@code GET /auth/verify} без токена тоже вернёт 200 с подсказкой.
     */
    @GetMapping("/auth/verify")
    public String magicLinkFallback() {
        return "auth/verify";
    }
}

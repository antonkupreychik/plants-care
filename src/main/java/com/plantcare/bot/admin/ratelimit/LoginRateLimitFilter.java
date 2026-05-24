package com.plantcare.bot.admin.ratelimit;

import com.plantcare.bot.observability.SentryTags;
import com.plantcare.bot.observability.SentryTags.Layer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Перехватывает только POST /admin/login. Если IP в чёрном списке → 429
 * с Retry-After: 60. Срабатывает ПЕРЕД UsernamePasswordAuthenticationFilter,
 * чтобы атакующий не нагружал BCrypt (cost=12, медленный by design).
 *
 * <p>Регистрация как @Bean — в {@code AdminSecurityConfig}, чтобы слайс-тесты
 * (@WebMvcTest) не пытались создать его автоматически (см. комментарий там).
 */
@RequiredArgsConstructor
@Slf4j
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private final LoginRateLimiter rateLimiter;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean isLoginPost = HttpMethod.POST.matches(request.getMethod())
                && "/admin/login".equals(request.getRequestURI());

        if (isLoginPost) {
            // Issue #114: тег layer=admin на изолированном scope только на время
            // rate-check. На HTTP-потоке scope push/pop'ает Sentry-ContextWebFilter,
            // но withScope гарантирует, что admin-тег не утечёт за пределы фильтра
            // даже если порядок фильтров изменится. Возвращаем флаг «заблокировано»,
            // чтобы short-circuit 429 действительно прервал цепочку.
            boolean blocked = SentryTags.callWithLayer(Layer.ADMIN, "LoginRateLimitFilter", () -> {
                String ip = request.getRemoteAddr();
                if (rateLimiter.isBlocked(ip)) {
                    log.warn("Admin login rate-limit triggered: ip={}", ip);
                    return true;
                }
                return false;
            });
            if (blocked) {
                response.setStatus(429);
                response.setHeader("Retry-After", "60");
                response.getWriter().write("Too many login attempts. Try again in a minute.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

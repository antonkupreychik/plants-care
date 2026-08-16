package com.plantcare.core.observability;

import com.plantcare.core.errorlog.ErrorLogMdc;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Кладёт в MDC контекст запроса — correlation id, путь и id пользователя (issue #97).
 *
 * <p>Отсюда его забирает {@code ErrorLogDbAppender}: у записи в журнале ошибок появляется
 * ответ на «чей запрос и какой» без того, чтобы каждый {@code log.error} тащил это руками.
 *
 * <p><b>Порядок важен.</b> Фильтр регистрируется ПОСЛЕ цепочки Spring Security
 * (см. {@link MdcConfig}), иначе {@link SecurityContextHolder} ещё пуст и {@code userId}
 * всегда был бы {@code null}. Обратная сторона: ошибки внутри самой security-цепочки
 * (отказ аутентификации) в MDC-контекст не попадают.
 *
 * <p>Correlation id берётся из заголовка {@code X-Correlation-Id} (или {@code X-Request-Id}),
 * если клиент его прислал, иначе генерируется. Он же возвращается в ответе тем же
 * заголовком — так поддержка может связать жалобу юзера с конкретной строкой в
 * {@code /admin/errors}.
 *
 * <p>MDC — ThreadLocal, а пул потоков переиспользуется, поэтому очистка в {@code finally}
 * обязательна: иначе следующий запрос на том же потоке унаследует чужой userId.
 */
public class MdcContextFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** Длина сгенерированного correlation id: половины UUID без дефисов хватает. */
    private static final int GENERATED_ID_LENGTH = 16;

    private static final String ANONYMOUS_PRINCIPAL = "anonymousUser";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = resolveCorrelationId(request);
        MDC.put(ErrorLogMdc.CORRELATION_ID, correlationId);
        MDC.put(ErrorLogMdc.REQUEST_PATH, request.getRequestURI());
        String userId = currentUserId();
        if (userId != null) {
            MDC.put(ErrorLogMdc.USER_ID, userId);
        }
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(ErrorLogMdc.CORRELATION_ID);
            MDC.remove(ErrorLogMdc.REQUEST_PATH);
            MDC.remove(ErrorLogMdc.USER_ID);
        }
    }

    private static String resolveCorrelationId(HttpServletRequest request) {
        String fromHeader = firstNonBlank(
                request.getHeader(CORRELATION_ID_HEADER),
                request.getHeader(REQUEST_ID_HEADER));
        if (fromHeader == null) {
            return UUID.randomUUID().toString().replace("-", "").substring(0, GENERATED_ID_LENGTH);
        }
        // Заголовок приходит снаружи — обрезаем под ширину колонки error_logs.correlation_id.
        return fromHeader.length() > 64 ? fromHeader.substring(0, 64) : fromHeader;
    }

    /**
     * {@code users.id} строкой. Для JWT-цепочки {@code auth.getName()} — это claim
     * {@code sub}, то есть ровно {@code users.id} (см. {@code CurrentUserProvider}).
     * Для admin-сессии там логин админа — он не число, и аппендер запишет {@code null}.
     */
    private static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || ANONYMOUS_PRINCIPAL.equals(auth.getPrincipal())) {
            return null;
        }
        return auth.getName();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }
}

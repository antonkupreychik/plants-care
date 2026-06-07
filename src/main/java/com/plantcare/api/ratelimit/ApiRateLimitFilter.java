package com.plantcare.api.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.plantcare.core.ratelimit.RateLimitEventService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Servlet filter applying per-IP and per-user rate limits using a sliding-window
 * counter backed by Caffeine (issue #92).
 *
 * <p>Each key (IP or user-id) gets an {@link AtomicLong} hit-counter stored in a
 * Caffeine cache with a TTL matching the window size. When the entry expires the
 * counter is evicted and the key starts fresh in the next window.
 *
 * <ul>
 *   <li>Unauthenticated requests: 100 req/min per IP
 *   <li>Authenticated requests: 1000 req/min per user
 *   <li>Auth endpoints (/api/v1/auth/apple, /api/v1/auth/google): 10 req/min per IP
 *   <li>Magic-link (/api/v1/auth/email/request): 3 req/min + 5 req/hour per IP
 * </ul>
 *
 * <p>All limits configurable via {@link ApiRateLimitProperties}.
 * Filter only runs for {@code /api/**} paths.
 */
@Slf4j
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final String LIMIT_TYPE_UNAUTH = "UNAUTHENTICATED_PER_IP";
    private static final String LIMIT_TYPE_AUTH = "AUTHENTICATED_PER_USER";
    private static final String LIMIT_TYPE_AUTH_ENDPOINT = "AUTH_ENDPOINT_PER_IP";
    private static final String LIMIT_TYPE_MAGIC_LINK_MINUTE = "MAGIC_LINK_PER_MINUTE";
    private static final String LIMIT_TYPE_MAGIC_LINK_HOUR = "MAGIC_LINK_PER_HOUR";

    private final ApiRateLimitProperties props;
    private final RateLimitEventService eventService;

    // Separate caches for each limit bucket so TTLs remain independent
    private final Cache<String, AtomicLong> unauthCache;
    private final Cache<String, AtomicLong> authCache;
    private final Cache<String, AtomicLong> authEndpointCache;
    private final Cache<String, AtomicLong> magicLinkMinuteCache;
    private final Cache<String, AtomicLong> magicLinkHourCache;

    public ApiRateLimitFilter(ApiRateLimitProperties props, RateLimitEventService eventService) {
        this.props = props;
        this.eventService = eventService;
        long max = props.maxBuckets();
        this.unauthCache = buildCache(max, Duration.ofMinutes(1));
        this.authCache = buildCache(max, Duration.ofMinutes(1));
        this.authEndpointCache = buildCache(max, Duration.ofMinutes(1));
        this.magicLinkMinuteCache = buildCache(max, Duration.ofMinutes(1));
        this.magicLinkHourCache = buildCache(max, Duration.ofHours(1));
    }

    private static Cache<String, AtomicLong> buildCache(long maxSize, Duration window) {
        return Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(window)
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String ip = extractIp(request);
        String path = request.getRequestURI();

        if (isMagicLinkEndpoint(path)) {
            if (!tryConsume(magicLinkMinuteCache, "ml-min:" + ip,
                    props.magicLinkPerMinute(), 60L, ip, path, LIMIT_TYPE_MAGIC_LINK_MINUTE, response)) return;
            if (!tryConsume(magicLinkHourCache, "ml-hour:" + ip,
                    props.magicLinkPerHour(), 3600L, ip, path, LIMIT_TYPE_MAGIC_LINK_HOUR, response)) return;

        } else if (isAuthEndpoint(path)) {
            if (!tryConsume(authEndpointCache, "auth-ep:" + ip,
                    props.authEndpointPerMinute(), 60L, ip, path, LIMIT_TYPE_AUTH_ENDPOINT, response)) return;

        } else {
            String userId = extractUserId();
            if (userId != null) {
                if (!tryConsume(authCache, "user:" + userId,
                        props.authenticatedPerMinute(), 60L, ip, path, LIMIT_TYPE_AUTH, response)) return;
            } else {
                if (!tryConsume(unauthCache, "ip:" + ip,
                        props.unauthenticatedPerMinute(), 60L, ip, path, LIMIT_TYPE_UNAUTH, response)) return;
            }
        }

        chain.doFilter(request, response);
    }

    /**
     * Increments counter for {@code key}. Returns {@code false} and writes 429 if limit exceeded.
     *
     * @param windowSeconds used for the {@code Retry-After} header estimation
     */
    private boolean tryConsume(Cache<String, AtomicLong> cache, String key,
                                int limit, long windowSeconds,
                                String ip, String path, String limitType,
                                HttpServletResponse response) throws IOException {
        AtomicLong counter = cache.asMap().computeIfAbsent(key, k -> new AtomicLong(0));
        long current = counter.incrementAndGet();
        if (current <= limit) {
            return true;
        }
        // Exceeded — write 429
        Long userId = extractUserIdLong();
        eventService.record(ip, userId, path, limitType);
        long retryAfter = Math.max(1L, windowSeconds - (current - limit));
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests. Retry after "
                + retryAfter + " seconds.\"}}");
        return false;
    }

    private static boolean isAuthEndpoint(String path) {
        return path.startsWith("/api/v1/auth/apple") || path.startsWith("/api/v1/auth/google");
    }

    private static boolean isMagicLinkEndpoint(String path) {
        return path.startsWith("/api/v1/auth/email/request");
    }

    private static String extractIp(HttpServletRequest request) {
        // forward-headers-strategy: framework resolves X-Forwarded-For automatically
        String ip = request.getRemoteAddr();
        return ip != null ? ip : "unknown";
    }

    private static String extractUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return null;
    }

    private static Long extractUserIdLong() {
        String name = extractUserId();
        if (name == null) return null;
        try {
            return Long.parseLong(name);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

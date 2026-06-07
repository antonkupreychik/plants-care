package com.plantcare.api.ratelimit;

import com.plantcare.core.ratelimit.RateLimitEventService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ApiRateLimitFilter} (issue #92).
 * Uses tight limits to verify 429 behaviour quickly.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiRateLimitFilter — token bucket per-IP rate limiting")
class ApiRateLimitFilterTest {

    @Mock
    private RateLimitEventService eventService;

    @Mock
    private FilterChain filterChain;

    private ApiRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        // Tight limits for testing: 2 req/min unauthenticated, 3 req/min auth endpoint, 1 magic-link/min
        var props = new ApiRateLimitProperties(2, 10, 3, 1, 2, 10_000);
        filter = new ApiRateLimitFilter(props, eventService);
    }

    @Test
    @DisplayName("should_allow_requests_within_limit_when_unauthenticated")
    void should_allow_requests_within_limit_when_unauthenticated() throws Exception {
        var request = buildRequest("/api/v1/plants", "10.0.0.1");
        var response = new MockHttpServletResponse();

        // First 2 requests should pass
        filter.doFilter(request, response, filterChain);
        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(2)).doFilter(any(), any());
        assertThat(response.getStatus()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    @DisplayName("should_return_429_when_unauthenticated_limit_exceeded")
    void should_return_429_when_unauthenticated_limit_exceeded() throws Exception {
        var request = buildRequest("/api/v1/plants", "10.0.0.2");

        // Exhaust the 2-per-minute limit
        filter.doFilter(request, new MockHttpServletResponse(), filterChain);
        filter.doFilter(request, new MockHttpServletResponse(), filterChain);

        // Third request should be rate-limited
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(response.getHeader("Retry-After")).isNotNull();
        verify(filterChain, times(2)).doFilter(any(), any());
        verify(eventService).record(eq("10.0.0.2"), isNull(), eq("/api/v1/plants"),
                eq("UNAUTHENTICATED_PER_IP"));
    }

    @Test
    @DisplayName("should_return_429_on_magic_link_endpoint_when_limit_exceeded")
    void should_return_429_on_magic_link_endpoint_when_limit_exceeded() throws Exception {
        var request = buildRequest("/api/v1/auth/email/request", "10.0.0.3");

        // Exhaust limit (1 per minute)
        filter.doFilter(request, new MockHttpServletResponse(), filterChain);

        // Second request should be rate-limited
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(response.getHeader("Retry-After")).isNotNull();
        verify(eventService).record(eq("10.0.0.3"), isNull(), eq("/api/v1/auth/email/request"),
                eq("MAGIC_LINK_PER_MINUTE"));
    }

    @Test
    @DisplayName("should_not_filter_non_api_paths")
    void should_not_filter_non_api_paths() throws Exception {
        var request = buildRequest("/admin/rate-limits", "10.0.0.4");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        // shouldNotFilter returns true → doFilterInternal is not called → chain proceeds
        verify(filterChain, times(1)).doFilter(any(), any());
        assertThat(response.getStatus()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    @DisplayName("should_use_stricter_limit_for_auth_endpoints")
    void should_use_stricter_limit_for_auth_endpoints() throws Exception {
        var request = buildRequest("/api/v1/auth/apple", "10.0.0.5");

        // Exhaust 3-per-minute auth endpoint limit
        for (int i = 0; i < 3; i++) {
            filter.doFilter(request, new MockHttpServletResponse(), filterChain);
        }

        // 4th should be rate-limited
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        verify(eventService).record(eq("10.0.0.5"), isNull(), eq("/api/v1/auth/apple"),
                eq("AUTH_ENDPOINT_PER_IP"));
    }

    @Test
    @DisplayName("should_isolate_buckets_per_ip")
    void should_isolate_buckets_per_ip() throws Exception {
        // Two different IPs should have independent buckets
        var requestA = buildRequest("/api/v1/plants", "192.168.1.1");
        var requestB = buildRequest("/api/v1/plants", "192.168.1.2");

        // Exhaust IP A
        filter.doFilter(requestA, new MockHttpServletResponse(), filterChain);
        filter.doFilter(requestA, new MockHttpServletResponse(), filterChain);
        var limitedA = new MockHttpServletResponse();
        filter.doFilter(requestA, limitedA, filterChain);
        assertThat(limitedA.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        // IP B should still be allowed
        var responseB = new MockHttpServletResponse();
        filter.doFilter(requestB, responseB, filterChain);
        assertThat(responseB.getStatus()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    @DisplayName("should_include_retry_after_header_in_429_response")
    void should_include_retry_after_header_in_429_response() throws Exception {
        var request = buildRequest("/api/v1/plants", "10.0.0.6");

        filter.doFilter(request, new MockHttpServletResponse(), filterChain);
        filter.doFilter(request, new MockHttpServletResponse(), filterChain);

        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        String retryAfter = response.getHeader("Retry-After");
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isPositive();
    }

    private static MockHttpServletRequest buildRequest(String path, String ip) {
        var req = new MockHttpServletRequest("GET", path);
        req.setRemoteAddr(ip);
        return req;
    }
}

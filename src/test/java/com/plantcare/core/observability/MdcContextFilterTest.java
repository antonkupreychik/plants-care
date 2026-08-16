package com.plantcare.core.observability;

import com.plantcare.core.errorlog.ErrorLogMdc;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Заполнение MDC контекстом запроса (issue #97). Именно отсюда журнал ошибок получает
 * user_id / request_path / correlation_id.
 */
@DisplayName("MdcContextFilter — контекст запроса в MDC (#97)")
class MdcContextFilterTest {

    private final MdcContextFilter filter = new MdcContextFilter();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    @DisplayName("should_expose_user_path_and_correlation_when_request_is_authenticated")
    void should_expose_user_path_and_correlation_when_request_is_authenticated() throws Exception {
        authenticateAs("42");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/plants");
        request.addHeader(MdcContextFilter.CORRELATION_ID_HEADER, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Map<String, String> seen = captureMdc(request, response);

        assertThat(seen).containsEntry(ErrorLogMdc.USER_ID, "42");
        assertThat(seen).containsEntry(ErrorLogMdc.REQUEST_PATH, "/api/v1/plants");
        assertThat(seen).containsEntry(ErrorLogMdc.CORRELATION_ID, "abc-123");
        assertThat(response.getHeader(MdcContextFilter.CORRELATION_ID_HEADER)).isEqualTo("abc-123");
    }

    @Test
    @DisplayName("should_generate_correlation_id_when_no_header_is_sent")
    void should_generate_correlation_id_when_no_header_is_sent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        Map<String, String> seen = captureMdc(new MockHttpServletRequest("GET", "/api/v1/health"), response);

        assertThat(seen.get(ErrorLogMdc.CORRELATION_ID)).isNotBlank().hasSize(16);
        assertThat(response.getHeader(MdcContextFilter.CORRELATION_ID_HEADER))
                .isEqualTo(seen.get(ErrorLogMdc.CORRELATION_ID));
    }

    @Test
    @DisplayName("should_fall_back_to_request_id_header_when_correlation_header_is_absent")
    void should_fall_back_to_request_id_header_when_correlation_header_is_absent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/plants");
        request.addHeader(MdcContextFilter.REQUEST_ID_HEADER, "req-9");

        Map<String, String> seen = captureMdc(request, new MockHttpServletResponse());

        assertThat(seen).containsEntry(ErrorLogMdc.CORRELATION_ID, "req-9");
    }

    @Test
    @DisplayName("should_truncate_correlation_id_when_client_sends_oversized_header")
    void should_truncate_correlation_id_when_client_sends_oversized_header() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/plants");
        request.addHeader(MdcContextFilter.CORRELATION_ID_HEADER, "x".repeat(500));

        Map<String, String> seen = captureMdc(request, new MockHttpServletResponse());

        assertThat(seen.get(ErrorLogMdc.CORRELATION_ID)).hasSize(64);
    }

    @Test
    @DisplayName("should_omit_user_id_when_caller_is_anonymous")
    void should_omit_user_id_when_caller_is_anonymous() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        Map<String, String> seen = captureMdc(
                new MockHttpServletRequest("GET", "/api/v1/health"), new MockHttpServletResponse());

        assertThat(seen).doesNotContainKey(ErrorLogMdc.USER_ID);
    }

    /**
     * Потоки переиспользуются пулом — незачищенный MDC утёк бы на следующий запрос
     * и приписал чужому юзеру чужую ошибку.
     */
    @Test
    @DisplayName("should_clear_mdc_when_request_is_finished")
    void should_clear_mdc_when_request_is_finished() throws Exception {
        authenticateAs("42");

        captureMdc(new MockHttpServletRequest("GET", "/api/v1/plants"), new MockHttpServletResponse());

        assertThat(MDC.get(ErrorLogMdc.USER_ID)).isNull();
        assertThat(MDC.get(ErrorLogMdc.REQUEST_PATH)).isNull();
        assertThat(MDC.get(ErrorLogMdc.CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("should_clear_mdc_when_downstream_throws")
    void should_clear_mdc_when_downstream_throws() {
        authenticateAs("42");
        FilterChain boom = (req, res) -> {
            throw new IllegalStateException("downstream failed");
        };

        try {
            filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/plants"),
                    new MockHttpServletResponse(), boom);
        } catch (Exception expected) {
            // ожидаемо пробрасывается наружу
        }

        assertThat(MDC.get(ErrorLogMdc.CORRELATION_ID)).isNull();
    }

    /** Прогоняет фильтр и возвращает снимок MDC, каким его увидел бы логгер внутри запроса. */
    private Map<String, String> captureMdc(MockHttpServletRequest request,
                                           MockHttpServletResponse response) throws Exception {
        Map<String, String> seen = new HashMap<>();
        FilterChain chain = (req, res) -> {
            Map<String, String> snapshot = MDC.getCopyOfContextMap();
            if (snapshot != null) {
                seen.putAll(snapshot);
            }
        };
        filter.doFilter(request, response, chain);
        return seen;
    }

    private static void authenticateAs(String name) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(name, "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_USER")));
    }
}

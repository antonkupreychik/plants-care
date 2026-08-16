package com.plantcare.api.errorlog;

import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.errorlog.ErrorLogRecorder;
import com.plantcare.core.observability.MdcContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Сквозной тест журнала ошибок (issue #97, ключевой AC):
 * «бросить exception в API → появилась запись с правильным user_id и correlation_id».
 *
 * <p>Проверяется вся цепочка целиком: MDC-фильтр → {@code ApiExceptionHandler.log.error}
 * → Logback-аппендер → очередь → батч-инсерт в {@code error_logs}.
 */
@AutoConfigureMockMvc
@Import(ErrorLogBoomController.class)
@DisplayName("Журнал ошибок — exception в API долетает до error_logs (#97)")
class ErrorLogPipelineTest extends IntegrationTestBase {

    private static final long USER_ID = 777L;
    private static final String CORRELATION_ID = "it-corr-97";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ErrorLogRecorder recorder;

    @AfterEach
    void cleanup() {
        jdbc.execute("DELETE FROM error_logs");
    }

    @Test
    @DisplayName("should_persist_error_with_user_and_correlation_when_api_throws")
    void should_persist_error_with_user_and_correlation_when_api_throws() throws Exception {
        mockMvc.perform(get(ErrorLogBoomController.PATH)
                        .header(MdcContextFilter.CORRELATION_ID_HEADER, CORRELATION_ID)
                        .with(jwt().jwt(token -> token.subject(String.valueOf(USER_ID)))))
                .andExpect(status().isInternalServerError());

        Map<String, Object> row = awaitRow(CORRELATION_ID);

        assertThat(row.get("level")).isEqualTo("ERROR");
        assertThat(row.get("user_id")).isEqualTo(USER_ID);
        assertThat(row.get("correlation_id")).isEqualTo(CORRELATION_ID);
        assertThat(row.get("request_path")).isEqualTo(ErrorLogBoomController.PATH);
        assertThat((String) row.get("stack_trace")).contains("UnsupportedOperationException");
        assertThat((String) row.get("fingerprint"))
                .startsWith("java.lang.UnsupportedOperationException at ");
    }

    @Test
    @DisplayName("should_generate_correlation_id_when_client_sends_none")
    void should_generate_correlation_id_when_client_sends_none() throws Exception {
        var result = mockMvc.perform(get(ErrorLogBoomController.PATH)
                        .with(jwt().jwt(token -> token.subject(String.valueOf(USER_ID)))))
                .andExpect(status().isInternalServerError())
                .andReturn();

        String generated = result.getResponse().getHeader(MdcContextFilter.CORRELATION_ID_HEADER);
        assertThat(generated).isNotBlank();

        Map<String, Object> row = awaitRow(generated);
        assertThat(row.get("user_id")).isEqualTo(USER_ID);
    }

    /**
     * Инсерт асинхронный: сначала подталкиваем слив, потом ждём появления строки.
     * Фоновый флашер мог забрать батч раньше нас — оба пути ведут в БД.
     */
    private Map<String, Object> awaitRow(String correlationId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            recorder.flush();
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM error_logs WHERE correlation_id = ?", correlationId);
            if (!rows.isEmpty()) {
                return rows.getFirst();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No error_logs row appeared for correlationId=" + correlationId);
    }
}

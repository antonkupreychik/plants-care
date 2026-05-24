package com.plantcare.bot.controller.api;

import com.plantcare.bot.service.CalendarExportService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-слайс тесты {@link CalendarController}.
 *
 * <p>{@code addFilters = false} убирает Spring Security filter chain — нам нужно проверить
 * только контроллер: маппинг URL, заголовки, маппинг {@link EntityNotFoundException} в 404.
 * Реальный security (открыт через default chain) проверяется в {@link CalendarControllerIT}.
 */
@WebMvcTest(controllers = CalendarController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("Web-слайс CalendarController — выдача .ics")
class CalendarControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private CalendarExportService calendarExportService;

    @Test
    @DisplayName("200 OK + Content-Type text/calendar и тело ICS из сервиса")
    void should_return_200_with_text_calendar_content_type() throws Exception {
        String ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n";
        when(calendarExportService.buildIcsForToken("abc123")).thenReturn(ics);

        mockMvc.perform(get("/calendar/abc123.ics"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/calendar;charset=utf-8"))
                .andExpect(content().string(ics));
    }

    @Test
    @DisplayName("Заголовок Cache-Control запрещает кэширование подписок")
    void should_return_cache_control_header() throws Exception {
        when(calendarExportService.buildIcsForToken("xyz"))
                .thenReturn("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n");

        mockMvc.perform(get("/calendar/xyz.ics"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache, must-revalidate"));
    }

    @Test
    @DisplayName("404 Not Found, если сервис кидает EntityNotFoundException")
    void should_return_404_when_token_unknown() throws Exception {
        when(calendarExportService.buildIcsForToken("nope"))
                .thenThrow(new EntityNotFoundException("Calendar token not found"));

        mockMvc.perform(get("/calendar/nope.ics"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("В сервис прокидывается ровно тот токен, что в URL")
    void should_serve_ics_at_token_url() throws Exception {
        when(calendarExportService.buildIcsForToken("path-token-123"))
                .thenReturn("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n");

        mockMvc.perform(get("/calendar/path-token-123.ics"))
                .andExpect(status().isOk());

        verify(calendarExportService).buildIcsForToken(eq("path-token-123"));
    }
}

package com.plantcare.bot.controller.api;

import com.plantcare.core.service.CalendarExportService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issue #79: публичная точка для подписки на .ics календарь.
 *
 * <p>URL вида {@code GET /calendar/{token}.ics} — Read-Only, без авторизации
 * (резолв по токену). Все задачи рендерятся как All-Day события.
 *
 * <p>Маршрут не под {@code /admin/**}, поэтому подпадает под default
 * SecurityFilterChain с {@code permitAll}.
 */
@RestController
@RequiredArgsConstructor
public class CalendarController {

    private final CalendarExportService calendarExportService;

    @GetMapping(value = "/calendar/{token}.ics", produces = "text/calendar; charset=utf-8")
    public ResponseEntity<String> getCalendar(@PathVariable String token) {
        String ics = calendarExportService.buildIcsForToken(token);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/calendar; charset=utf-8"))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, must-revalidate")
                .body(ics);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Void> handleNotFound(EntityNotFoundException e) {
        return ResponseEntity.notFound().build();
    }
}

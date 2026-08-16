package com.plantcare.admin.errors.controller;

import com.plantcare.admin.config.AdminSecurityConfig;
import com.plantcare.admin.errors.dto.ErrorLogDetailDto;
import com.plantcare.admin.errors.dto.ErrorLogFilter;
import com.plantcare.admin.errors.service.AdminErrorsService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

/**
 * {@code /admin/errors} — Errors viewer (issue #97).
 *
 * <ul>
 *   <li>{@code GET /admin/errors} — топ-10 уникальных ошибок за 24 часа + полный список
 *       с комбинируемыми фильтрами (дата, юзер, логгер, подстрока в сообщении, уровень);</li>
 *   <li>{@code GET /admin/errors/{id}} — деталка: полный стек и контекст;</li>
 *   <li>{@code GET /admin/errors/{id}/user-context} — HTMX-фрагмент «все события юзера
 *       за 1 час до ошибки».</li>
 * </ul>
 *
 * Контроллер тонкий: разбор параметров → сервис → модель.
 */
@Controller
@ConditionalOnExpression(AdminSecurityConfig.ADMIN_ENABLED_EXPR)
@RequiredArgsConstructor
public class AdminErrorsController {

    private final AdminErrorsService service;

    @GetMapping("/admin/errors")
    public String list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String logger,
            @RequestParam(required = false) String message,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String fingerprint,
            @RequestParam(defaultValue = "1") int page,
            Model model
    ) {
        ErrorLogFilter filter = new ErrorLogFilter(from, to, userId, logger, message, level, fingerprint);

        model.addAttribute("activeMenu", "errors");
        model.addAttribute("topGroups", service.topGroups());
        model.addAttribute("topWindowHours", AdminErrorsService.TOP_WINDOW.toHours());
        model.addAttribute("errors", service.page(filter, page));
        model.addAttribute("filter", filter);
        return "admin/errors/list";
    }

    @GetMapping("/admin/errors/{id}")
    public String detail(@PathVariable long id, Model model) {
        ErrorLogDetailDto error = service.detail(id);
        if (error == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Error log entry not found: " + id);
        }
        model.addAttribute("activeMenu", "errors");
        model.addAttribute("error", error);
        model.addAttribute("contextWindowMinutes", AdminErrorsService.USER_CONTEXT_WINDOW.toMinutes());
        return "admin/errors/detail";
    }

    /**
     * HTMX-фрагмент под кнопкой «найти все события юзера за 1 час до ошибки».
     * Отдаётся кусок разметки, а не целая страница — деталка подменяет им блок на месте.
     */
    @GetMapping("/admin/errors/{id}/user-context")
    public String userContext(@PathVariable long id, Model model) {
        model.addAttribute("contextEvents", service.userContext(id));
        model.addAttribute("contextWindowMinutes", AdminErrorsService.USER_CONTEXT_WINDOW.toMinutes());
        return "admin/errors/_user-context :: userContext";
    }
}

package com.plantcare.admin.users.controller;

import com.plantcare.admin.config.AdminSecurityConfig;
import com.plantcare.admin.users.dto.AuthProviderKind;
import com.plantcare.admin.users.service.AdminUserAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Секция «Аутентификация» и «История привязок» на странице юзера (issue #93).
 *
 * <ul>
 *   <li>{@code POST /auth/unlink} — разрыв привязки, редирект обратно на страницу
 *       юзера (как остальные мутации в {@link AdminUserActionsController});</li>
 *   <li>{@code GET  /auth/link-history} — HTMX-фрагмент истории с пагинацией и
 *       фильтром по дате.</li>
 * </ul>
 */
@Controller
@RequestMapping("/admin/users/{id}/auth")
@ConditionalOnExpression(AdminSecurityConfig.ADMIN_ENABLED_EXPR)
@RequiredArgsConstructor
public class AdminUserAuthController {

    static final String HISTORY_FRAGMENT = "admin/_auth-link-history :: linkHistoryFragment";

    private final AdminUserAuthService service;

    @PostMapping("/unlink")
    public String unlink(@PathVariable long id,
                         @RequestParam AuthProviderKind provider,
                         Authentication auth,
                         RedirectAttributes ra) {
        try {
            var result = service.unlink(id, provider, auth.getName());
            ra.addFlashAttribute("flash",
                    "🔗 Привязка разорвана: " + provider.getLabel());
            if (!result.canStillAuthenticate()) {
                ra.addFlashAttribute("flashError",
                        "⚠️ У юзера не осталось ни одного способа входа — залогиниться он не сможет");
            }
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flashError", "❌ " + e.getMessage());
        }
        return "redirect:/admin/users/" + id;
    }

    @GetMapping("/link-history")
    public String linkHistory(@PathVariable long id,
                              @RequestParam(required = false) String from,
                              @RequestParam(required = false) String to,
                              @RequestParam(required = false, defaultValue = "1") int page,
                              Model model) {
        model.addAttribute("userId", id);
        model.addAttribute("linkHistory",
                service.loadLinkHistory(id, parseDate(from), parseDate(to), page));
        return HISTORY_FRAGMENT;
    }

    /**
     * Даты фильтра принимаем строками, а не {@code @DateTimeFormat LocalDate}:
     * пустой инпут (и {@code hx-include} по форме, и очистка поля пользователем)
     * присылает {@code from=} — пустую строку, на которой конвертер LocalDate
     * отвечает 400. Мусор в параметре трактуем как «фильтр не задан»: это
     * вспомогательный фильтр админки, ронять из-за него секцию незачем.
     */
    static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}

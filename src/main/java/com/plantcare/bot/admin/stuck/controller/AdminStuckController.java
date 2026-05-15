package com.plantcare.bot.admin.stuck.controller;

import com.plantcare.bot.admin.config.AdminSecurityConfig;
import com.plantcare.bot.admin.stuck.service.AdminStuckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * /admin/stuck — Stuck Users (issue #59, Task 4 — вторая половина).
 *
 * <p>GET — список юзеров, застрявших не-IDLE дольше {@link AdminStuckService#STUCK_AFTER_HOURS}.
 * <p>POST /reset-bulk — массовый сброс выбранных. Одиночный сброс делается через
 * существующий {@code POST /admin/users/{id}/reset-state} (переиспользование Task 2).
 */
@Controller
@ConditionalOnExpression(AdminSecurityConfig.ADMIN_ENABLED_EXPR)
@RequiredArgsConstructor
@Slf4j
public class AdminStuckController {

    private final AdminStuckService service;

    @GetMapping("/admin/stuck")
    public String page(Model model) {
        model.addAttribute("activeMenu", "stuck");
        model.addAttribute("stuck", service.listStuck());
        return "admin/stuck";
    }

    /**
     * Массовый сброс. Тело — {@code application/x-www-form-urlencoded} с
     * полем {@code userIds=12&userIds=34&...} (стандартный multi-checkbox).
     * Если ничего не выбрано — мягкий редирект с warning'ом.
     */
    @PostMapping("/admin/stuck/reset-bulk")
    public String resetBulk(
            @RequestParam(name = "userIds", required = false) List<Long> userIds,
            Authentication auth,
            RedirectAttributes ra
    ) {
        if (userIds == null || userIds.isEmpty()) {
            ra.addFlashAttribute("flashError", "Ничего не выбрано");
            return "redirect:/admin/stuck";
        }

        log.info("Admin {} bulk stuck-reset for {} users", auth.getName(), userIds.size());
        AdminStuckService.BulkResetResult result = service.resetBulk(userIds, auth.getName());

        if (result.failed() == 0) {
            ra.addFlashAttribute("flash",
                    "✅ Сброшено " + result.success() + " " + userWord(result.success()));
        } else if (result.success() == 0) {
            ra.addFlashAttribute("flashError",
                    "Не удалось сбросить ни одного юзера (" + result.failed() + " ошибок)");
        } else {
            ra.addFlashAttribute("flash",
                    "Сброшено " + result.success() + " из " + result.total()
                            + " (ошибок: " + result.failed() + ")");
        }
        return "redirect:/admin/stuck";
    }

    private String userWord(int n) {
        int mod10 = n % 10;
        int mod100 = n % 100;
        if (mod10 == 1 && mod100 != 11) return "юзера";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "юзеров";
        return "юзеров";
    }
}

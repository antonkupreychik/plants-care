package com.plantcare.bot.admin.users.controller;

import com.plantcare.bot.admin.config.AdminSecurityConfig;
import com.plantcare.bot.admin.featureflag.service.FeatureFlagService;
import com.plantcare.bot.admin.users.service.AdminUserActionService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Controller
@RequestMapping("/admin/users/{id}")
@ConditionalOnExpression(AdminSecurityConfig.ADMIN_ENABLED_EXPR)
@RequiredArgsConstructor
public class AdminUserActionsController {

    private final AdminUserActionService service;
    private final FeatureFlagService flagService;

    @PostMapping("/reset-state")
    public String resetState(@PathVariable long id, Authentication auth, RedirectAttributes ra) {
        service.resetState(id, auth.getName());
        ra.addFlashAttribute("flash", "Состояние сброшено");
        return "redirect:/admin/users/" + id;
    }

    @PostMapping("/toggle-block")
    public String toggleBlock(@PathVariable long id, Authentication auth, RedirectAttributes ra) {
        boolean newBlocked = service.toggleBlock(id, auth.getName());
        ra.addFlashAttribute("flash", newBlocked ? "Юзер заблокирован" : "Юзер разблокирован");
        return "redirect:/admin/users/" + id;
    }

    @PostMapping("/pause")
    public String pause(@PathVariable long id,
                        @RequestParam(required = false) Integer days,
                        @RequestParam(required = false)
                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate until,
                        Authentication auth, RedirectAttributes ra) {
        Instant pausedUntil;
        if (days != null && days > 0) {
            pausedUntil = Instant.now().plus(Duration.ofDays(days));
        } else if (until != null) {
            pausedUntil = until.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        } else {
            ra.addFlashAttribute("flashError", "Укажи дни или дату");
            return "redirect:/admin/users/" + id;
        }
        try {
            Instant result = service.pause(id, pausedUntil, auth.getName());
            ra.addFlashAttribute("flash", "На паузе до " + result);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/admin/users/" + id;
    }

    @PostMapping("/unpause")
    public String unpause(@PathVariable long id, Authentication auth, RedirectAttributes ra) {
        service.unpause(id, auth.getName());
        ra.addFlashAttribute("flash", "Пауза снята");
        return "redirect:/admin/users/" + id;
    }

    @PostMapping("/send-message")
    public String sendMessage(@PathVariable long id, @RequestParam String text,
                              Authentication auth, RedirectAttributes ra) {
        var result = service.sendMessage(id, text, auth.getName());
        if (result.success()) {
            ra.addFlashAttribute("flash", "Сообщение отправлено");
        } else {
            ra.addFlashAttribute("flashError", "Не отправлено: " + result.error());
        }
        return "redirect:/admin/users/" + id;
    }

    // ===== Feature flags (issue #78) =====

    @PostMapping("/flags/set")
    public String setFlag(
            @PathVariable long id,
            @RequestParam String code,
            @RequestParam(defaultValue = "true") boolean enabled,
            Authentication auth, RedirectAttributes ra
    ) {
        try {
            boolean changed = flagService.setFlag(id, code, enabled, auth.getName());
            if (changed) {
                ra.addFlashAttribute("flash",
                        (enabled ? "🚩 Флаг включён: " : "🏳️ Флаг снят: ") + code);
            } else {
                ra.addFlashAttribute("flash",
                        "Состояние флага не изменилось: " + code);
            }
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flashError", "❌ " + e.getMessage());
        }
        return "redirect:/admin/users/" + id;
    }

    @PostMapping("/flags/clear")
    public String clearFlag(
            @PathVariable long id,
            @RequestParam String code,
            Authentication auth, RedirectAttributes ra
    ) {
        try {
            boolean changed = flagService.clearFlag(id, code, auth.getName());
            ra.addFlashAttribute("flash",
                    changed ? "🏳️ Флаг снят: " + code : "Флаг и так не был установлен: " + code);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flashError", "❌ " + e.getMessage());
        }
        return "redirect:/admin/users/" + id;
    }
}

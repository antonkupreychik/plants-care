package com.plantcare.admin.scheduler.controller;

import com.plantcare.admin.config.AdminSecurityConfig;
import com.plantcare.admin.scheduler.service.AdminSchedulerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * /admin/scheduler — Scheduler Health (issue #59, часть Task 4).
 *
 * <p>GET — снепшот: last tick, queue size, sent 24h, sends-by-hour таблица.
 * <p>POST /trigger — ручной запуск тика; защищён 30-сек cooldown'ом.
 */
@Controller
@ConditionalOnExpression(AdminSecurityConfig.ADMIN_ENABLED_EXPR)
@RequiredArgsConstructor
@Slf4j
public class AdminSchedulerController {

    private final AdminSchedulerService service;

    @GetMapping("/admin/scheduler")
    public String page(Model model) {
        model.addAttribute("activeMenu", "scheduler");
        model.addAttribute("page", service.buildPage());
        model.addAttribute("adminZone", service.getAdminZone().getId());
        return "admin/scheduler";
    }

    @PostMapping("/admin/scheduler/trigger")
    public String trigger(Authentication auth, RedirectAttributes ra) {
        log.info("Manual scheduler trigger requested by {}", auth.getName());
        AdminSchedulerService.TriggerResult result = service.triggerTick();
        if (result.isSuccess()) {
            ra.addFlashAttribute("flash",
                    "Запущено, обработано " + result.processed() + " "
                            + scheduleWord(result.processed()));
        } else if (result.isCooldown()) {
            ra.addFlashAttribute("flashError",
                    "⏳ Только что запускали. Подожди " + result.cooldownSecondsLeft() + " сек.");
        } else {
            ra.addFlashAttribute("flashError",
                    "Tick упал: " + (result.error() == null ? "неизвестная ошибка" : result.error()));
        }
        return "redirect:/admin/scheduler";
    }

    /**
     * Отправить пуш по конкретному расписанию (issue #59).
     * Параметр {@code force} — query/form param: если true, обходим фильтры
     * (паузу, quiet-hours, дедуп). Это переключатель в UI: «Отправить» (force=false)
     * и «Принудительно» (force=true).
     */
    @PostMapping("/admin/scheduler/schedule/{id}/send")
    public String sendOne(
            @PathVariable Long id,
            @RequestParam(name = "force", defaultValue = "false") boolean force,
            Authentication auth,
            RedirectAttributes ra
    ) {
        log.info("Admin {} sending schedule {} (force={})", auth.getName(), id, force);
        var result = service.sendOne(id, force);
        if (result.isSent()) {
            ra.addFlashAttribute("flash",
                    force ? "🚀 Принудительно отправлено (расписание #" + id + ")"
                          : "✅ Отправлено (расписание #" + id + ")");
        } else if (result.isSkipped()) {
            ra.addFlashAttribute("flashError",
                    "Пропущено: " + result.reason());
        } else if (result.isNotFound()) {
            ra.addFlashAttribute("flashError",
                    "Расписание не найдено");
        } else {
            ra.addFlashAttribute("flashError",
                    "Не удалось: " + (result.reason() == null ? "ошибка" : result.reason()));
        }
        return "redirect:/admin/scheduler";
    }

    /**
     * Пропустить ближайший пуш по расписанию (issue #59). Та же семантика, что
     * у кнопки «Пропустить» в боте — {@code next_due_at += interval_days}.
     */
    @PostMapping("/admin/scheduler/schedule/{id}/skip")
    public String skipOne(@PathVariable Long id, Authentication auth, RedirectAttributes ra) {
        log.info("Admin {} skipping schedule {}", auth.getName(), id);
        boolean ok = service.skipOne(id);
        if (ok) {
            ra.addFlashAttribute("flash",
                    "⏭ Пропущено (расписание #" + id + ", сдвинут next_due_at)");
        } else {
            ra.addFlashAttribute("flashError",
                    "Расписание не найдено");
        }
        return "redirect:/admin/scheduler";
    }

    /** Грамматика: 1 расписание / 2-4 расписания / 5+ расписаний. */
    private String scheduleWord(int n) {
        int mod10 = n % 10;
        int mod100 = n % 100;
        if (mod10 == 1 && mod100 != 11) return "расписание";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "расписания";
        return "расписаний";
    }
}

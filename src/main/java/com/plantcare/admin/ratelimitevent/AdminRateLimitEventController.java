package com.plantcare.admin.ratelimitevent;

import com.plantcare.admin.config.AdminSecurityConfig;
import com.plantcare.core.ratelimit.RateLimitEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Admin page: top rate-limit violators for outlier analysis (issue #92).
 * Available at {@code GET /admin/rate-limits}.
 */
@Controller
@ConditionalOnExpression(AdminSecurityConfig.ADMIN_ENABLED_EXPR)
@RequiredArgsConstructor
public class AdminRateLimitEventController {

    private final RateLimitEventService service;

    @GetMapping("/admin/rate-limits")
    public String rateLimits(@RequestParam(defaultValue = "24") int hours, Model model) {
        model.addAttribute("activeMenu", "rate-limits");
        model.addAttribute("hours", hours);
        model.addAttribute("totalEvents", service.countLastHours(hours));
        model.addAttribute("topViolators", service.findTopViolators(hours, 20));
        return "admin/rate-limits";
    }
}

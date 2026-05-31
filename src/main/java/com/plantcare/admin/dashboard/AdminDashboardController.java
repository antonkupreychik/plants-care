package com.plantcare.admin.dashboard;

import com.plantcare.admin.config.AdminSecurityConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * GET /admin → Dashboard.
 * Заменяет временную заглушку из AdminLoginController.
 */
@Controller
@ConditionalOnExpression(AdminSecurityConfig.ADMIN_ENABLED_EXPR)
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardService service;

    @GetMapping("/admin")
    public String dashboard(Model model) {
        model.addAttribute("activeMenu", "dashboard");
        model.addAttribute("dashboard", service.loadDashboard());
        return "admin/index";
    }
}

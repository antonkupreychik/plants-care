package com.plantcare.admin.controller;

import com.plantcare.admin.config.AdminSecurityConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** GET-эндпоинты админки. POST /admin/login обрабатывается Spring Security. */
@Controller
@ConditionalOnExpression(AdminSecurityConfig.ADMIN_ENABLED_EXPR)
public class AdminLoginController {

    @GetMapping("/admin/login")
    public String loginPage(@RequestParam(required = false) String error, Model model) {
        if (error != null) {
            model.addAttribute("error", "Неверный логин или пароль");
        }
        return "admin/login";
    }
}

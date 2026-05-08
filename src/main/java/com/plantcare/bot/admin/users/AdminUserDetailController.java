package com.plantcare.bot.admin.users;

import com.plantcare.bot.admin.config.AdminSecurityConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

@Controller
@ConditionalOnExpression(AdminSecurityConfig.ADMIN_ENABLED_EXPR)
@RequiredArgsConstructor
public class AdminUserDetailController {

    private final AdminUserDetailService service;

    @GetMapping("/admin/users/{id}")
    public String detail(@PathVariable long id, Model model) {
        var user = service.loadDetail(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        model.addAttribute("activeMenu", "users");
        model.addAttribute("user", user);
        return "admin/user-detail";
    }
}

package com.plantcare.admin.users.controller;

import com.plantcare.admin.config.AdminSecurityConfig;
import com.plantcare.admin.users.service.AdminUserListService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * /admin/users — full page с layout.
 * /admin/users/_fragment — только tableFragment, для HTMX подгрузки фильтров/поиска.
 */
@Controller
@ConditionalOnExpression(AdminSecurityConfig.ADMIN_ENABLED_EXPR)
@RequiredArgsConstructor
public class AdminUserListController {

    private final AdminUserListService service;

    @GetMapping("/admin/users")
    public String list(@RequestParam(required = false) String filter,
                       @RequestParam(required = false) String search,
                       @RequestParam(required = false, defaultValue = "last_action") String sort,
                       @RequestParam(required = false, defaultValue = "desc") String direction,
                       @RequestParam(required = false, defaultValue = "1") int page,
                       Model model) {
        model.addAttribute("activeMenu", "users");
        model.addAttribute("page", service.find(filter, search, sort, direction, page));
        return "admin/users";
    }

    @GetMapping("/admin/users/_fragment")
    public String fragment(@RequestParam(required = false) String filter,
                           @RequestParam(required = false) String search,
                           @RequestParam(required = false, defaultValue = "last_action") String sort,
                           @RequestParam(required = false, defaultValue = "desc") String direction,
                           @RequestParam(required = false, defaultValue = "1") int page,
                           Model model) {
        model.addAttribute("page", service.find(filter, search, sort, direction, page));
        return "admin/_users-table :: tableFragment";
    }
}

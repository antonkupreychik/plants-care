package com.plantcare.admin.storage.controller;

import com.plantcare.admin.config.AdminSecurityConfig;
import com.plantcare.admin.storage.service.AdminStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * /admin/storage — управление хранилищем фото (issue #101).
 *
 * <p>Только чтение: карточки объёма и расходов, график роста за полгода, топ
 * юзеров по объёму и пагинированный список последних загрузок. Действия
 * (удаление, восстановление, GDPR-чистка) — в {@link AdminPhotoActionsController}.
 */
@Controller
@ConditionalOnExpression(AdminSecurityConfig.ADMIN_ENABLED_EXPR)
@RequiredArgsConstructor
public class AdminStorageController {

    private final AdminStorageService service;

    @GetMapping("/admin/storage")
    public String page(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("activeMenu", "storage");
        model.addAttribute("storage", service.loadPage(page));
        return "admin/storage";
    }
}

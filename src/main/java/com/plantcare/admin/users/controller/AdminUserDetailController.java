package com.plantcare.admin.users.controller;

import com.plantcare.admin.config.AdminSecurityConfig;
import com.plantcare.admin.storage.service.AdminStorageService;
import com.plantcare.admin.users.service.AdminUserDetailService;
import com.plantcare.core.domain.featureflag.FeatureFlag;
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
    private final AdminStorageService storageService;

    @GetMapping("/admin/users/{id}")
    public String detail(@PathVariable long id, Model model) {
        var user = service.loadDetail(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        model.addAttribute("activeMenu", "users");
        model.addAttribute("user", user);
        // Issue #101: грид фото юзера — просмотр и точечное удаление по запросу
        // (в том числе GDPR). Пресайн-ссылки собираются в сервисе; если бакет не
        // сконфигурирован, previewUrl == null и шаблон рисует плейсхолдер.
        model.addAttribute("photos", storageService.loadUserPhotos(id));
        // Каталог известных флагов — рендерим их toggle'ами; ad-hoc флаги
        // (которые в каталог не входят, но включены у юзера) — отдельной секцией.
        model.addAttribute("flagCatalog", FeatureFlag.CATALOG);
        // Map<code, описание> для рендера labels рядом с toggle'ами
        java.util.Map<String, String> descriptions = new java.util.LinkedHashMap<>();
        for (String code : FeatureFlag.CATALOG) {
            descriptions.put(code, FeatureFlag.describe(code));
        }
        model.addAttribute("flagDescriptions", descriptions);
        return "admin/user-detail";
    }
}

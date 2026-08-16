package com.plantcare.admin.audit.controller;

import com.plantcare.admin.audit.AdminAuditAction;
import com.plantcare.admin.audit.dto.AdminAuditFilter;
import com.plantcare.admin.audit.service.AdminAuditService;
import com.plantcare.admin.config.AdminSecurityConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Страница аудит-лога админских действий — {@code /admin/audit} (issue #98).
 *
 * <p>Тонкий контроллер: собирает {@link AdminAuditFilter} из query-параметров,
 * отдаёт страницу или тот же срез в CSV. Никакой логики фильтрации здесь нет —
 * она в {@link AdminAuditService}.
 */
@Controller
@RequestMapping("/admin/audit")
@ConditionalOnExpression(AdminSecurityConfig.ADMIN_ENABLED_EXPR)
@RequiredArgsConstructor
public class AdminAuditController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    /**
     * BOM — единственный способ заставить Excel открыть UTF-8 CSV с кириллицей
     * вместо кракозябр. Байтами, а не строковым литералом: символ невидимый,
     * и в исходнике его слишком легко потерять при переформатировании.
     */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final AdminAuditService service;

    @GetMapping
    public String page(@RequestParam(name = "admin", required = false) String admin,
                       @RequestParam(name = "action", required = false) String action,
                       @RequestParam(name = "targetType", required = false) String targetType,
                       @RequestParam(name = "from", required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(name = "to", required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(name = "page", defaultValue = "1") int page,
                       Model model) {
        AdminAuditFilter filter = AdminAuditFilter.of(admin, action, targetType, from, to);

        model.addAttribute("activeMenu", "audit");
        model.addAttribute("page", service.search(filter, page));
        model.addAttribute("admins", service.knownAdmins());
        model.addAttribute("targetTypes", service.knownTargetTypes());
        model.addAttribute("actions", AdminAuditAction.values());
        // Обратно в форму кладём то, что реально применилось: невалидный action
        // схлопывается в «все», и селект не должен показывать несуществующий выбор.
        model.addAttribute("selectedAdmin", filter.adminUsername());
        model.addAttribute("selectedAction", filter.action() == null ? null : filter.action().name());
        model.addAttribute("selectedTargetType", filter.targetType());
        model.addAttribute("selectedFrom", from);
        model.addAttribute("selectedTo", to);
        return "admin/audit";
    }

    /** Экспорт текущей выборки в CSV — те же фильтры, без пагинации. */
    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(name = "admin", required = false) String admin,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "targetType", required = false) String targetType,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        byte[] csv = service.exportCsv(AdminAuditFilter.of(admin, action, targetType, from, to))
                .getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[UTF8_BOM.length + csv.length];
        System.arraycopy(UTF8_BOM, 0, body, 0, UTF8_BOM.length);
        System.arraycopy(csv, 0, body, UTF8_BOM.length, csv.length);

        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("admin-audit-" + LocalDate.now() + ".csv")
                        .build().toString())
                .body(body);
    }
}

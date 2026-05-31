package com.plantcare.admin.broadcast.controller;

import com.plantcare.admin.broadcast.repository.AdminBroadcastRepository;
import com.plantcare.admin.broadcast.service.AdminBroadcastService;
import com.plantcare.admin.broadcast.service.AudienceResolver;
import com.plantcare.admin.config.AdminSecurityConfig;
import com.plantcare.core.domain.AdminBroadcast;
import com.plantcare.core.domain.enums.AudienceFilter;
import com.plantcare.core.domain.enums.BroadcastStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.domain.Limit;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * /admin/broadcast — рассылка сообщений (issue #60).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /admin/broadcast} — главная страница (форма + лог последних 20)</li>
 *   <li>{@code POST /admin/broadcast/send} — запуск рассылки</li>
 *   <li>{@code GET /admin/broadcast/count} — HTMX-фрагмент: «Получат N юзеров»</li>
 *   <li>{@code GET /admin/broadcast/preview} — HTMX-фрагмент: preview сообщения</li>
 *   <li>{@code GET /admin/broadcast/{id}/progress} — HTMX-фрагмент: текущий прогресс</li>
 *   <li>{@code POST /admin/broadcast/{id}/stop} — остановить активную рассылку</li>
 * </ul>
 */
@Controller
@ConditionalOnExpression(AdminSecurityConfig.ADMIN_ENABLED_EXPR)
@RequiredArgsConstructor
@Slf4j
public class AdminBroadcastController {

    /** Защита от двойного подтверждения — должен прийти ровно "SEND". */
    private static final String CONFIRM_TOKEN = "SEND";

    private final AdminBroadcastService service;
    private final AudienceResolver audienceResolver;
    private final AdminBroadcastRepository broadcastRepository;

    @GetMapping("/admin/broadcast")
    public String page(Model model) {
        model.addAttribute("activeMenu", "broadcast");
        model.addAttribute("timezones", audienceResolver.availableTimezones());
        model.addAttribute("audiences", AudienceFilter.values());
        model.addAttribute("defaultFilter", AudienceFilter.ONLINE);
        model.addAttribute("recent", broadcastRepository
                .findAllByOrderByCreatedAtDesc(Limit.of(20)));
        // Если есть RUNNING — показываем progress-блок поверх формы
        broadcastRepository.findFirstByStatus(BroadcastStatus.RUNNING)
                .ifPresent(b -> model.addAttribute("running", b));
        return "admin/broadcast";
    }

    @PostMapping("/admin/broadcast/send")
    public String send(
            @RequestParam(name = "text", required = false) String text,
            @RequestParam(name = "filter", required = false, defaultValue = "ONLINE") AudienceFilter filter,
            @RequestParam(name = "timezones", required = false) List<String> timezones,
            @RequestParam(name = "confirm", required = false) String confirm,
            Authentication auth,
            RedirectAttributes ra
    ) {
        if (!CONFIRM_TOKEN.equals(confirm)) {
            log.warn("Broadcast attempt without proper confirm token by {}", auth.getName());
            ra.addFlashAttribute("flashError",
                    "❌ Подтверждение не прошло. Введите SEND в поле подтверждения.");
            return "redirect:/admin/broadcast";
        }

        Set<String> tzSet = timezones == null ? Set.of() : new HashSet<>(timezones);
        AdminBroadcastService.StartResult result = service.startBroadcast(
                text, filter, tzSet, auth.getName());

        if (result.isStarted()) {
            ra.addFlashAttribute("flash",
                    "🚀 Рассылка запущена (id=" + result.broadcastId() + ")");
        } else if (result.isBusy()) {
            ra.addFlashAttribute("flashError",
                    "⏳ Уже идёт рассылка id=" + result.broadcastId()
                            + ". Дождитесь или остановите её.");
        } else {
            ra.addFlashAttribute("flashError", "❌ " + result.reason());
        }
        return "redirect:/admin/broadcast";
    }

    /** Счётчик аудитории (HTMX обновляет при смене radio/multi-select). */
    @GetMapping("/admin/broadcast/count")
    public String count(
            @RequestParam(name = "filter", defaultValue = "ONLINE") AudienceFilter filter,
            @RequestParam(name = "timezones", required = false) List<String> timezones,
            Model model
    ) {
        Set<String> tzSet = timezones == null ? Set.of() : new HashSet<>(timezones);
        long n = audienceResolver.count(filter, tzSet);
        model.addAttribute("count", n);
        return "admin/broadcast/_counter :: counter";
    }

    /**
     * Live-preview текста (HTMX, debounce 300ms на стороне клиента).
     * Всегда показывает экранированную версию — то, что реально отправится.
     */
    @GetMapping("/admin/broadcast/preview")
    public String preview(
            @RequestParam(name = "text", required = false) String text,
            Model model
    ) {
        String shown = AdminBroadcastService.escapeMarkdownV2(text == null ? "" : text);
        model.addAttribute("previewText", shown);
        model.addAttribute("length", shown.length());
        return "admin/broadcast/_preview :: preview";
    }

    /** Прогресс рассылки — HTMX-фрагмент, polling раз в 2 сек. */
    @GetMapping("/admin/broadcast/{id}/progress")
    public String progress(@PathVariable Long id, Model model) {
        Optional<AdminBroadcast> snap = service.snapshot(id);
        if (snap.isEmpty()) {
            return "admin/broadcast/_progress :: notFound";
        }
        model.addAttribute("b", snap.get());
        return "admin/broadcast/_progress :: progress";
    }

    @PostMapping("/admin/broadcast/{id}/stop")
    public String stop(@PathVariable Long id, Authentication auth, RedirectAttributes ra) {
        log.info("Admin {} stopping broadcast {}", auth.getName(), id);
        boolean stopped = service.requestStop(id);
        if (stopped) {
            ra.addFlashAttribute("flash", "⏹ Останов запрошен (id=" + id + ")");
        } else {
            ra.addFlashAttribute("flashError",
                    "Рассылка id=" + id + " уже завершена");
        }
        return "redirect:/admin/broadcast";
    }

    /** ENUM binding из строки в form — Spring сам справляется. Endpoint для прогресса
     *  без рендера, если фронту нужен только raw JSON — оставим на будущее. */
    @GetMapping("/admin/broadcast/{id}/progress.json")
    @org.springframework.web.bind.annotation.ResponseBody
    public ResponseEntity<AdminBroadcast> progressJson(@PathVariable Long id) {
        return service.snapshot(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

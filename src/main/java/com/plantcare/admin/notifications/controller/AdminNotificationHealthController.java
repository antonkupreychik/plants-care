package com.plantcare.admin.notifications.controller;

import com.plantcare.admin.config.AdminSecurityConfig;
import com.plantcare.admin.notifications.service.AdminNotificationHealthService;
import com.plantcare.admin.notifications.service.AdminNotificationHealthService.TestPushResult;
import com.plantcare.admin.users.dto.SendMessageResult;
import com.plantcare.admin.users.service.AdminUserActionService;
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
import org.springframework.web.util.UriComponentsBuilder;

/**
 * /admin/notifications/health — health-дашборд каналов уведомлений (issue #95).
 *
 * <p>GET — страница целиком; GET {@code /_fragment} — тот же контент без layout,
 * его раз в минуту тянет HTMX-поллинг (AC: «обновление раз в минуту»).
 *
 * <p>POST-действия применяются к «проблемным» юзерам: отцепить мёртвые
 * push-токены и послать тестовое уведомление в любой из каналов.
 */
@Controller
@ConditionalOnExpression(AdminSecurityConfig.ADMIN_ENABLED_EXPR)
@RequiredArgsConstructor
@Slf4j
public class AdminNotificationHealthController {

    public static final String PATH = "/admin/notifications/health";

    private final AdminNotificationHealthService service;
    private final AdminUserActionService userActionService;

    @GetMapping(PATH)
    public String page(
            @RequestParam(name = "hours", defaultValue = "" + AdminNotificationHealthService.DEFAULT_HOURS) int hours,
            @RequestParam(name = "channel", required = false) String channel,
            Model model
    ) {
        model.addAttribute("activeMenu", "notifications-health");
        model.addAttribute("health", service.loadHealth(hours, channel));
        return "admin/notifications/health";
    }

    /**
     * Фрагмент для HTMX-поллинга. Возвращает ровно тот же блок, что и страница,
     * но без layout — HTMX подменяет им {@code #health-body}.
     */
    @GetMapping(PATH + "/_fragment")
    public String fragment(
            @RequestParam(name = "hours", defaultValue = "" + AdminNotificationHealthService.DEFAULT_HOURS) int hours,
            @RequestParam(name = "channel", required = false) String channel,
            Model model
    ) {
        model.addAttribute("health", service.loadHealth(hours, channel));
        return "admin/notifications/_health-body :: body";
    }

    /** «Отписать токен»: снести все push-устройства юзера. */
    @PostMapping(PATH + "/users/{userId}/prune-devices")
    public String pruneDevices(
            @PathVariable long userId,
            @RequestParam(name = "hours", required = false) Integer hours,
            @RequestParam(name = "channel", required = false) String channel,
            Authentication auth,
            RedirectAttributes ra
    ) {
        int deleted = service.pruneDevices(userId, auth.getName());
        if (deleted > 0) {
            ra.addFlashAttribute("flash", "✅ Отцеплено устройств: " + deleted + " (юзер #" + userId + ")");
        } else {
            ra.addFlashAttribute("flashError", "У юзера #" + userId + " не было зарегистрированных устройств");
        }
        return redirect(hours, channel);
    }

    /** «Отправить тестовое уведомление» в push-канал. */
    @PostMapping(PATH + "/users/{userId}/test-push")
    public String testPush(
            @PathVariable long userId,
            @RequestParam(name = "hours", required = false) Integer hours,
            @RequestParam(name = "channel", required = false) String channel,
            Authentication auth,
            RedirectAttributes ra
    ) {
        TestPushResult result = service.sendTestPush(userId, auth.getName());
        if (result.total() == 0) {
            ra.addFlashAttribute("flashError", "У юзера #" + userId + " нет устройств для push");
        } else if (result.sent() == result.total()) {
            ra.addFlashAttribute("flash", "✅ Тестовый push принят провайдером (" + result.sent() + " устр.)");
        } else {
            ra.addFlashAttribute("flashError", "Тестовый push: ok=" + result.sent()
                    + ", мёртвый токен=" + result.stale() + ", ошибка=" + result.failed());
        }
        return redirect(hours, channel);
    }

    /**
     * «Отправить тестовое уведомление» в Telegram — переиспользует существующее
     * действие админки, а не заводит второй путь отправки.
     */
    @PostMapping(PATH + "/users/{userId}/test-telegram")
    public String testTelegram(
            @PathVariable long userId,
            @RequestParam(name = "hours", required = false) Integer hours,
            @RequestParam(name = "channel", required = false) String channel,
            Authentication auth,
            RedirectAttributes ra
    ) {
        SendMessageResult result = userActionService.sendMessage(
                userId, AdminNotificationHealthService.TEST_NOTIFICATION_TEXT, auth.getName());
        if (result.success()) {
            ra.addFlashAttribute("flash", "✅ Тестовое сообщение отправлено в Telegram (юзер #" + userId + ")");
        } else {
            ra.addFlashAttribute("flashError", "Telegram: " + result.error());
        }
        return redirect(hours, channel);
    }

    /** Редирект обратно на страницу с сохранением активных фильтров. */
    private static String redirect(Integer hours, String channel) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(PATH);
        if (hours != null) {
            builder.queryParam("hours", hours);
        }
        if (channel != null && !channel.isBlank()) {
            builder.queryParam("channel", channel);
        }
        return "redirect:" + builder.build().toUriString();
    }
}

package com.plantcare.admin.storage.controller;

import com.plantcare.admin.config.AdminSecurityConfig;
import com.plantcare.admin.storage.service.AdminPhotoService;
import com.plantcare.admin.storage.service.PhotoPurgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Действия админа над фото (issue #101): удаление, возврат и GDPR-чистка.
 *
 * <p>Все три — soft-delete. Физически объект уходит из бакета отложенно, через
 * {@code admin.storage.retention-days} дней, силами
 * {@code StorageMaintenanceScheduler}. Пока retention не истёк, удаление
 * обратимо — в этом и смысл окна.
 *
 * <p>{@code returnTo} — куда вернуться после действия: со страницы юзера в грид
 * фото, со /admin/storage — обратно в список. Значение валидируется на
 * префикс {@code /admin/}, чтобы форма не превратилась в open redirect.
 */
@Slf4j
@Controller
@ConditionalOnExpression(AdminSecurityConfig.ADMIN_ENABLED_EXPR)
@RequiredArgsConstructor
public class AdminPhotoActionsController {

    private static final String DEFAULT_RETURN = "/admin/storage";

    private final AdminPhotoService photoService;
    private final PhotoPurgeService purgeService;

    /** Удалить одно фото (soft-delete). Физически уйдёт из бакета по retention. */
    @PostMapping("/admin/photos/{photoId}/delete")
    public String deletePhoto(
            @PathVariable long photoId,
            @RequestParam(required = false) String returnTo,
            Authentication auth,
            RedirectAttributes ra
    ) {
        boolean deleted = photoService.softDelete(photoId, auth.getName());
        if (deleted) {
            ra.addFlashAttribute("flash",
                    "🗑 Фото #" + photoId + " удалено — физически будет вычищено по истечении retention");
        } else {
            ra.addFlashAttribute("flash", "Фото #" + photoId + " уже было удалено");
        }
        return redirect(returnTo);
    }

    /** Вернуть ошибочно удалённое фото. Работает, пока объект не вычищен из бакета. */
    @PostMapping("/admin/photos/{photoId}/restore")
    public String restorePhoto(
            @PathVariable long photoId,
            @RequestParam(required = false) String returnTo,
            Authentication auth,
            RedirectAttributes ra
    ) {
        try {
            boolean restored = photoService.restore(photoId, auth.getName());
            ra.addFlashAttribute("flash", restored
                    ? "♻️ Фото #" + photoId + " восстановлено"
                    : "Фото #" + photoId + " и так не было удалено");
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("flashError", "❌ " + e.getMessage());
        }
        return redirect(returnTo);
    }

    /** GDPR-запрос: удалить все фото юзера. Массовый soft-delete, чистка отложенная. */
    @PostMapping("/admin/users/{userId}/photos/delete-all")
    public String deleteAllUserPhotos(
            @PathVariable long userId,
            Authentication auth,
            RedirectAttributes ra
    ) {
        int affected = photoService.softDeleteAllForUser(userId, auth.getName());
        if (affected == 0) {
            ra.addFlashAttribute("flash", "У юзера нет активных фото — удалять нечего");
        } else {
            ra.addFlashAttribute("flash",
                    "🗑 Удалено фото: " + affected + " — физическая чистка по истечении retention");
        }
        return "redirect:/admin/users/" + userId;
    }

    /**
     * Ручной запуск чистки бакета — тот же прогон, что делает суточный шедулер.
     * Нужен, чтобы не ждать ночи при разборе GDPR-запроса.
     */
    @PostMapping("/admin/storage/purge-now")
    public String purgeNow(Authentication auth, RedirectAttributes ra) {
        log.info("Admin action STORAGE_PURGE_NOW: admin={}", auth.getName());
        PhotoPurgeService.PurgeResult result = purgeService.purgeExpired();

        if (result.total() == 0) {
            ra.addFlashAttribute("flash", "Нечего чистить — просроченных удалённых фото нет");
        } else if (result.failed() == 0) {
            ra.addFlashAttribute("flash", "🧹 Вычищено из бакета: " + result.purged());
        } else {
            ra.addFlashAttribute("flashError",
                    "Вычищено " + result.purged() + " из " + result.total()
                            + ", ошибок: " + result.failed() + " — повторится в следующем прогоне");
        }
        return "redirect:/admin/storage";
    }

    /**
     * Куда редиректим после действия. Пускаем только относительные пути внутрь
     * админки: иначе {@code returnTo} из формы стал бы open redirect.
     */
    private String redirect(String returnTo) {
        if (returnTo != null && returnTo.startsWith("/admin/") && !returnTo.startsWith("//")) {
            return "redirect:" + returnTo;
        }
        return "redirect:" + DEFAULT_RETURN;
    }
}

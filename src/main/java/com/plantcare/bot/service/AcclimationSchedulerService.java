package com.plantcare.bot.service;

import com.plantcare.bot.client.TelegramClientProvider;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Hourly-тик акклиматизации (issue #75):
 *   1) завершает режим, когда acclimation_until ≤ now() — отправляет
 *      «✅ Акклиматизация для {plant} завершена» и очищает поля;
 *   2) отправляет check-in вопросы «как выглядит растение?» по расписанию
 *      acclimation_checkin_next_at, не чаще раза в N дней.
 *
 * Уважает {@code user.isPaused()} и quiet_hours — отправки переносятся
 * на следующий тик, ничего не теряется.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AcclimationSchedulerService {

    private final PlantAcclimationService plantAcclimationService;
    private final TelegramClientProvider telegramClientProvider;

    @Scheduled(fixedRate = 3_600_000L)  // раз в час
    @Transactional
    public void tick() {
        LocalDateTime now = LocalDateTime.now();

        // 1) Финализация — растения, у которых истёк период акклиматизации.
        for (Plant plant : plantAcclimationService.findPlantsToFinish(now)) {
            try {
                User user = plant.getUser();
                if (user.isPaused() || isQuietHours(user, now)) {
                    continue;  // подождём до следующего тика
                }
                sendFinishMessage(user, plant);
                plantAcclimationService.finish(plant);
            } catch (Exception e) {
                log.error("Failed to finish acclimation for plant {}: {}",
                        plant.getId(), e.getMessage(), e);
            }
        }

        // 2) Check-in вопросы — растения, у которых пришло время следующего опроса.
        for (Plant plant : plantAcclimationService.findPlantsForCheckin(now)) {
            try {
                User user = plant.getUser();
                if (user.isPaused() || isQuietHours(user, now)) {
                    continue;
                }
                sendCheckinMessage(user, plant);
                plantAcclimationService.scheduleNextCheckin(plant);
            } catch (Exception e) {
                log.error("Failed to send acclimation checkin for plant {}: {}",
                        plant.getId(), e.getMessage(), e);
            }
        }
    }

    private void sendFinishMessage(User user, Plant plant) {
        SendMessage msg = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("✅ Акклиматизация для " + plant.getName() + " завершена. "
                        + "Возвращаюсь к обычному режиму уведомлений.")
                .build();
        try {
            telegramClientProvider.getTelegramClient().execute(msg);
            log.info("Acclimation finished message sent: plant={} chat={}",
                    plant.getId(), user.getTelegramChatId());
        } catch (TelegramApiException e) {
            log.error("Telegram send error (acclimation finish, plant={}): {}",
                    plant.getId(), e.getMessage(), e);
        }
    }

    private void sendCheckinMessage(User user, Plant plant) {
        InlineKeyboardButton ok = InlineKeyboardButton.builder()
                .text("👍 Ок")
                .callbackData("v1:accl_checkin:" + plant.getId() + ":OK")
                .build();
        InlineKeyboardButton wilt = InlineKeyboardButton.builder()
                .text("🥀 Вянет")
                .callbackData("v1:accl_checkin:" + plant.getId() + ":WILT")
                .build();
        InlineKeyboardButton yellow = InlineKeyboardButton.builder()
                .text("🟡 Желтеет")
                .callbackData("v1:accl_checkin:" + plant.getId() + ":YELLOW")
                .build();
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(ok, wilt, yellow))
                .build();

        SendMessage msg = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("👀 Как выглядит " + plant.getName() + "?")
                .replyMarkup(keyboard)
                .build();
        try {
            telegramClientProvider.getTelegramClient().execute(msg);
            log.info("Acclimation checkin sent: plant={} chat={}",
                    plant.getId(), user.getTelegramChatId());
        } catch (TelegramApiException e) {
            log.error("Telegram send error (acclimation checkin, plant={}): {}",
                    plant.getId(), e.getMessage(), e);
        }
    }

    /**
     * Минимальная реализация quiet-hours-проверки в local TZ юзера — копия логики
     * из {@code NotificationSchedulerService} (issue #28). Дублирование намеренно
     * мелкое, чтобы acclimation-тик мог переехать в отдельный модуль/класс
     * независимо от основного scheduler'а.
     */
    private boolean isQuietHours(User user, LocalDateTime now) {
        ZoneId userZone;
        try {
            userZone = ZoneId.of(user.getTimezone());
        } catch (Exception e) {
            userZone = ZoneId.of("UTC");
        }

        ZonedDateTime userNow = now.atZone(ZoneId.systemDefault()).withZoneSameInstant(userZone);
        LocalTime userTime = userNow.toLocalTime();

        LocalTime start = user.getQuietHoursStart();
        LocalTime end = user.getQuietHoursEnd();
        if (start == null || end == null || start.equals(end)) {
            return false;
        }

        if (start.isBefore(end)) {
            return !userTime.isBefore(start) && userTime.isBefore(end);
        }
        return !userTime.isBefore(start) || userTime.isBefore(end);
    }
}

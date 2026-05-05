package com.plantcare.bot.command.impl;

import com.plantcare.bot.command.interfaces.BotCommand;
import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.repository.CareScheduleRepository;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MenuCommand implements BotCommand {

    private final UserService userService;
    private final PlantRepository plantRepository;
    private final CareScheduleRepository careScheduleRepository;

    @Override
    public String getCommandName() {
        return "/menu";
    }

    @Override
    public void execute(Update update, TelegramClient client) {
        Long chatId = update.getMessage().getChatId();
        User user = userService.findByChatId(chatId)
                .orElseThrow(() -> new IllegalStateException("User not found"));

        long plantCount = plantRepository.countByUserIdAndArchivedAtIsNull(user.getId());

        // Конец сегодняшнего дня в таймзоне юзера → конвертируем в UTC
        LocalDateTime endOfTodayUtc = getEndOfTodayInUtc(user.getTimezone());
        List<CareSchedule> todaySchedules = careScheduleRepository.findUserSchedulesDueBefore(
                user.getId(), endOfTodayUtc);

        String text = buildMenuText(plantCount, todaySchedules);
        InlineKeyboardMarkup keyboard = buildMenuKeyboard();

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("Markdown")
                .replyMarkup(keyboard)
                .build();

        try {
            client.execute(message);
            log.info("Shown main menu to user {}", chatId);
        } catch (TelegramApiException e) {
            log.error("Failed to send menu to user {}: {}", chatId, e.getMessage(), e);
        }
    }

    private String buildMenuText(long plantCount, List<CareSchedule> todaySchedules) {
        StringBuilder sb = new StringBuilder();
        sb.append("🏠 *Главное меню*\n\n");
        sb.append("🌿 Растений: ").append(plantCount).append("\n\n");

        if (todaySchedules.isEmpty()) {
            sb.append("Сегодня всё в порядке 🌱");
        } else {
            sb.append("📋 *Сегодня нужно сделать:*\n");
            for (CareSchedule schedule : todaySchedules) {
                String emoji = switch (schedule.getTaskType()) {
                    case WATERING -> "💧";
                    case MISTING -> "💨";
                    case FERTILIZING -> "🧪";
                };
                sb.append(emoji).append(" ")
                        .append(schedule.getPlant().getName())
                        .append(" — ").append(taskLabel(schedule))
                        .append("\n");
            }
        }

        return sb.toString();
    }

    private String taskLabel(CareSchedule schedule) {
        return switch (schedule.getTaskType()) {
            case WATERING -> "полить";
            case MISTING -> "опрыскать";
            case FERTILIZING -> "удобрить";
        };
    }

    private InlineKeyboardMarkup buildMenuKeyboard() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("➕ Добавить растение")
                                .callbackData("MENU:ADD_PLANT")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("📋 Все растения")
                                .callbackData("MENU:ALL_PLANTS")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("⚙️ Настройки")
                                .callbackData("MENU:SETTINGS")
                                .build()
                )))
                .build();
    }

    /**
     * Конец сегодняшнего дня по таймзоне юзера, конвертированный в UTC.
     * Например, для Europe/Moscow (UTC+3) конец дня 23:59:59 = 20:59:59 UTC.
     */
    private LocalDateTime getEndOfTodayInUtc(String timezone) {
        ZoneId userZone;
        try {
            userZone = ZoneId.of(timezone);
        } catch (Exception e) {
            log.warn("Invalid timezone '{}', defaulting to UTC", timezone);
            userZone = ZoneId.of("UTC");
        }

        ZonedDateTime nowInUserZone = ZonedDateTime.now(userZone);
        ZonedDateTime endOfDay = nowInUserZone.toLocalDate()
                .atTime(LocalTime.of(23, 59, 59))
                .atZone(userZone);

        return endOfDay.withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime();
    }
}
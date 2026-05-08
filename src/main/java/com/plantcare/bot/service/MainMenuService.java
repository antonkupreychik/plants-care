package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.repository.CareScheduleRepository;
import com.plantcare.bot.repository.PlantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MainMenuService {

    private final PlantRepository plantRepository;
    private final CareScheduleRepository careScheduleRepository;
    private final LocationService locationService;

    public void sendMainMenu(User user, TelegramClient client) {
        long plantCount = plantRepository.countByUserIdAndArchivedAtIsNull(user.getId());

        LocalDateTime endOfTodayUtc = getEndOfTodayInUtc(user.getTimezone());

        List<CareSchedule> todaySchedules = careScheduleRepository.findUserSchedulesDueBefore(
                user.getId(),
                endOfTodayUtc
        );

        List<Location> locations = locationService.getUserLocations(user.getId());

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(buildMenuText(plantCount, todaySchedules, locations))
                .parseMode("Markdown")
                .replyMarkup(buildMenuKeyboard())
                .build();

        try {
            client.execute(message);
            log.info("Shown main menu to user {}", user.getTelegramChatId());
        } catch (TelegramApiException e) {
            log.error("Failed to send menu to user {}: {}", user.getTelegramChatId(), e.getMessage(), e);
        }
    }

    private String buildMenuText(
            long plantCount,
            List<CareSchedule> todaySchedules,
            List<Location> locations
    ) {
        StringBuilder sb = new StringBuilder();

        sb.append("🏠 *Главное меню*\n\n");
        sb.append("🌿 Растений: ").append(plantCount).append("\n\n");

        if (todaySchedules.isEmpty()) {
            sb.append("Сегодня всё в порядке 🌱");
            return sb.toString();
        }

        sb.append("📋 *Сегодня нужно сделать:*\n");

        if (locations.size() < 2) {
            appendFlatTasks(sb, todaySchedules);
            return sb.toString();
        }

        appendGroupedTasks(sb, todaySchedules, locations);
        return sb.toString();
    }

    private void appendFlatTasks(StringBuilder sb, List<CareSchedule> schedules) {
        for (CareSchedule schedule : schedules) {
            sb.append(formatTaskLine(schedule)).append("\n");
        }
    }

    private void appendGroupedTasks(
            StringBuilder sb,
            List<CareSchedule> schedules,
            List<Location> locations
    ) {
        List<Location> orderedLocations = new ArrayList<>(locations);

        orderedLocations.sort(
                Comparator
                        .comparing(Location::isDefaultLocation)
                        .thenComparing(Location::getCreatedAt)
        );

        Map<Long, List<CareSchedule>> schedulesByLocationId = new LinkedHashMap<>();

        for (Location location : orderedLocations) {
            schedulesByLocationId.put(location.getId(), new ArrayList<>());
        }

        for (CareSchedule schedule : schedules) {
            if (schedule.getPlant() == null || schedule.getPlant().getLocation() == null) {
                continue;
            }

            Long locationId = schedule.getPlant().getLocation().getId();

            schedulesByLocationId
                    .computeIfAbsent(locationId, ignored -> new ArrayList<>())
                    .add(schedule);
        }

        for (Location location : orderedLocations) {
            List<CareSchedule> locationSchedules = schedulesByLocationId.get(location.getId());

            if (locationSchedules == null || locationSchedules.isEmpty()) {
                continue;
            }

            sb.append("\n")
                    .append(location.getDisplayName())
                    .append("\n");

            for (CareSchedule schedule : locationSchedules) {
                sb.append(formatTaskLine(schedule)).append("\n");
            }
        }
    }

    private String formatTaskLine(CareSchedule schedule) {
        return taskEmoji(schedule)
                + " "
                + schedule.getPlant().getName()
                + " — "
                + taskLabel(schedule);
    }

    private String taskEmoji(CareSchedule schedule) {
        return switch (schedule.getTaskType()) {
            case WATERING -> "💧";
            case MISTING -> "💨";
            case FERTILIZING -> "🌿";
        };
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
                                .text("📍 Комнаты")
                                .callbackData("MENU:LOCATIONS")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("⚙️ Настройки")
                                .callbackData("MENU:SETTINGS")
                                .build()
                )))
                .build();
    }

    private LocalDateTime getEndOfTodayInUtc(String timezone) {
        ZoneId userZone;

        try {
            userZone = ZoneId.of(timezone);
        } catch (Exception e) {
            log.warn("Invalid timezone '{}', defaulting to UTC", timezone);
            userZone = ZoneId.of("UTC");
        }

        ZonedDateTime endOfDay = ZonedDateTime.now(userZone)
                .toLocalDate()
                .atTime(LocalTime.of(23, 59, 59))
                .atZone(userZone);

        return endOfDay.withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime();
    }
}
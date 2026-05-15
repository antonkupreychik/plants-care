package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.featureflag.FeatureFlag;
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
    private final CareHistoryService careHistoryService;

    public void sendMainMenu(User user, TelegramClient client) {
        long plantCount = plantRepository.countByUserIdAndArchivedAtIsNull(user.getId());

        LocalDateTime endOfTodayUtc = getEndOfTodayInUtc(user.getTimezone());

        List<CareSchedule> todaySchedules = careScheduleRepository.findUserSchedulesDueBefore(
                user.getId(),
                endOfTodayUtc
        );

        List<Location> locations = locationService.getUserLocations(user.getId());

        // Стрик показываем только если ≥ MIN_USER_STREAK_TO_SHOW (3) дней —
        // короткие серии не должны давить на эго в духе "ваш стрик 1 день".
        int userStreak = careHistoryService.computeUserStreak(user.getId(), user.getTimezone());

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(buildMenuText(plantCount, todaySchedules, locations, userStreak))
                .parseMode("Markdown")
                .replyMarkup(buildMenuKeyboard(user))
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
            List<Location> locations,
            int userStreak
    ) {
        StringBuilder sb = new StringBuilder();

        sb.append("🏠 *Главное меню*\n\n");

        if (userStreak >= CareHistoryService.MIN_USER_STREAK_TO_SHOW) {
            sb.append("🔥 Твой стрик: ").append(userStreak).append(" ")
                    .append(pluralizeDays(userStreak)).append("\n");
        }

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

    /**
     * Русское склонение: 1 день, 2 дня, 5 дней. Достаточно для UI меню.
     */
    private String pluralizeDays(int n) {
        int mod10 = n % 10;
        int mod100 = n % 100;
        if (mod10 == 1 && mod100 != 11) return "день";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "дня";
        return "дней";
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
            case SOIL_CHECK -> "🪴";
        };
    }

    private String taskLabel(CareSchedule schedule) {
        return switch (schedule.getTaskType()) {
            case WATERING -> "полить";
            case MISTING -> "опрыскать";
            case FERTILIZING -> "удобрить";
            case SOIL_CHECK -> "проверить грунт";
        };
    }

    private InlineKeyboardMarkup buildMenuKeyboard(User user) {
        // Календарь скрыт за feature flag (issue #78): пока обкатываем
        // на узком круге, в общем меню кнопки нет. Когда раскатим — уберём
        // условие или сменим логику на «по умолчанию включён».
        boolean calendarEnabled = user.hasFeature(FeatureFlag.CALENDAR);

        // Когда календаря нет, нижняя строка содержит только «Настройки» —
        // оставляем её отдельной кнопкой во всю ширину, а не парой.
        InlineKeyboardRow bottomRow = calendarEnabled
                ? new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("📅 Календарь")
                                .callbackData("MENU:CALENDAR")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("⚙️ Настройки")
                                .callbackData("MENU:SETTINGS")
                                .build()))
                : new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("⚙️ Настройки")
                                .callbackData("MENU:SETTINGS")
                                .build()));

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
                .keyboardRow(bottomRow)
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
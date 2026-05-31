package com.plantcare.bot.service;

import com.plantcare.core.service.LocationService;

import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocationMenuService {

    private final LocationService locationService;
    private final CareScheduleRepository careScheduleRepository;

    public void sendLocationsMenu(User user, TelegramClient client) {
        List<Location> locations = locationService.getUserLocations(user.getId());

        StringBuilder text = new StringBuilder();
        text.append("📍 *Мои комнаты*\n\n");

        if (locations.isEmpty()) {
            text.append("Пока комнат нет.");
        } else {
            for (Location location : locations) {
                long plantsCount = locationService.countPlantsInLocation(user.getId(), location.getId());

                text.append(location.getDisplayName())
                        .append(" — ")
                        .append(plantsCount)
                        .append(" ")
                        .append(formatPlantsCount(plantsCount));

                if (location.isDefaultLocation()) {
                    text.append(" · по умолчанию");
                }

                text.append("\n");
            }
        }

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(text.toString())
                .parseMode("Markdown")
                .replyMarkup(buildLocationsKeyboard(locations))
                .build();

        execute(client, message);
    }

    public void sendLocationScreen(User user, Long locationId, TelegramClient client) {
        Location location = locationService.getLocation(user.getId(), locationId);
        List<Plant> plants = locationService.getPlantsInLocation(user.getId(), locationId);

        StringBuilder text = new StringBuilder();
        text.append(location.getDisplayName()).append("\n\n");

        if (plants.isEmpty()) {
            text.append("В этой комнате пока нет растений.");
        } else {
            text.append("*Растения:*\n");

            for (Plant plant : plants) {
                text.append("• ").append(plant.getName()).append("\n");
            }

            text.append("\nНажми на растение ниже, чтобы открыть действия.");
        }

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(text.toString())
                .parseMode("Markdown")
                .replyMarkup(buildLocationScreenKeyboard(user.getId(), location, plants))
                .build();

        execute(client, message);
    }

    public void sendMovePlantDialog(User user, Long plantId, TelegramClient client) {
        Plant plant = locationService.getUserPlant(user.getId(), plantId);
        List<Location> locations = locationService.getUserLocations(user.getId());

        String text = "Куда переместить растение *" + plant.getName() + "*?";

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(text)
                .parseMode("Markdown")
                .replyMarkup(buildMovePlantKeyboard(plant, locations))
                .build();

        execute(client, message);
    }

    public void sendDeleteLocationDialog(User user, Long locationId, TelegramClient client) {
        Location location = locationService.getLocation(user.getId(), locationId);
        long plantsCount = locationService.countPlantsInLocation(user.getId(), locationId);

        if (location.isDefaultLocation()) {
            SendMessage message = SendMessage.builder()
                    .chatId(user.getTelegramChatId().toString())
                    .text("❌ Дефолтную комнату удалить нельзя.")
                    .build();

            execute(client, message);
            return;
        }

        List<Location> targetLocations = locationService.getUserLocations(user.getId()).stream()
                .filter(candidate -> !candidate.getId().equals(locationId))
                .toList();

        if (targetLocations.isEmpty()) {
            SendMessage message = SendMessage.builder()
                    .chatId(user.getTelegramChatId().toString())
                    .text("❌ Некуда перенести растения. Сначала создай другую комнату.")
                    .build();

            execute(client, message);
            return;
        }

        String text = String.format(
                "🗑 Удалить комнату *%s*?\n\n" +
                        "Растения из неё: *%d*.\n" +
                        "Выбери, куда их перенести:",
                location.getDisplayName(),
                plantsCount
        );

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(text)
                .parseMode("Markdown")
                .replyMarkup(buildDeleteLocationKeyboard(locationId, targetLocations))
                .build();

        execute(client, message);
    }

    private InlineKeyboardMarkup buildLocationsKeyboard(List<Location> locations) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        for (Location location : locations) {
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text(location.getDisplayName())
                            .callbackData("LOCATION:VIEW:" + location.getId())
                            .build()
            )));
        }

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("➕ Добавить комнату")
                        .callbackData("LOCATION:CREATE")
                        .build()
        )));

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ Назад")
                        .callbackData("MENU:BACK")
                        .build()
        )));

        return InlineKeyboardMarkup.builder()
                .keyboard(rows)
                .build();
    }

    private InlineKeyboardMarkup buildLocationScreenKeyboard(
            Long userId, Location location, List<Plant> plants
    ) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        for (Plant plant : plants) {
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text("🌿 " + plant.getName())
                            .callbackData("PLANT:VIEW:" + plant.getId() + ":LOC:" + location.getId())
                            .build()
            )));
        }

        // Массовый полив (issue #19) — показываем только если есть хотя бы одно
        // активное WATERING-расписание в этой локации. Иначе кнопка вводила бы
        // в заблуждение («нажал — ничего не произошло»).
        if (careScheduleRepository.hasActiveSchedulesInUserLocation(
                userId, location.getId(), TaskType.WATERING)) {
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text("💧 Полить все растения здесь")
                            .callbackData("v1:bulk_done:" + location.getId())
                            .build()
            )));
        }

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("✏️ Переименовать")
                        .callbackData("LOCATION:RENAME:" + location.getId())
                        .build(),
                InlineKeyboardButton.builder()
                        .text("😀 Сменить emoji")
                        .callbackData("LOCATION:EMOJI:" + location.getId())
                        .build()
        )));

        if (!location.isDefaultLocation()) {
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text("🗑 Удалить")
                            .callbackData("LOCATION:DELETE:" + location.getId())
                            .build()
            )));
        }

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ К комнатам")
                        .callbackData("MENU:LOCATIONS")
                        .build()
        )));

        return InlineKeyboardMarkup.builder()
                .keyboard(rows)
                .build();
    }

    private InlineKeyboardMarkup buildMovePlantKeyboard(Plant plant, List<Location> locations) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        Long currentLocationId = plant.getLocation() != null ? plant.getLocation().getId() : null;

        for (Location location : locations) {
            if (location.getId().equals(currentLocationId)) {
                continue;
            }

            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text(location.getDisplayName())
                            .callbackData("PLANT:MOVE_CONFIRM:" + plant.getId() + ":" + location.getId())
                            .build()
            )));
        }

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("Отмена")
                        .callbackData("PLANT:VIEW:" + plant.getId())
                        .build()
        )));

        return InlineKeyboardMarkup.builder()
                .keyboard(rows)
                .build();
    }

    private InlineKeyboardMarkup buildDeleteLocationKeyboard(
            Long locationId,
            List<Location> targetLocations
    ) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        for (Location target : targetLocations) {
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text(target.getDisplayName())
                            .callbackData("LOCATION:DELETE_CONFIRM:" + locationId + ":" + target.getId())
                            .build()
            )));
        }

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("Отмена")
                        .callbackData("LOCATION:VIEW:" + locationId)
                        .build()
        )));

        return InlineKeyboardMarkup.builder()
                .keyboard(rows)
                .build();
    }

    private String formatPlantsCount(long count) {
        long mod10 = count % 10;
        long mod100 = count % 100;

        if (mod10 == 1 && mod100 != 11) {
            return "растение";
        }

        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return "растения";
        }

        return "растений";
    }

    private void execute(TelegramClient client, SendMessage message) {
        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send location menu message", e);
        }
    }
}
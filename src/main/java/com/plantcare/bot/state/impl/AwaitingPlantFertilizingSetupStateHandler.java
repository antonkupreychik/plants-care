package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.service.MainMenuService;
import com.plantcare.bot.service.PlantService;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingPlantFertilizingSetupStateHandler implements StateHandler {

    private static final int DEFAULT_FERTILIZING_DAYS = 14;
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru"));

    private final UserService userService;
    private final PlantService plantService;
    private final PlantRepository plantRepository;
    private final MainMenuService mainMenuService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_PLANT_FERTILIZING_SETUP;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            handleCustomIntervalInput(user, update, client);
            return;
        }

        if (!update.hasCallbackQuery()) {
            return;
        }

        String callbackData = update.getCallbackQuery().getData();
        answerCallback(client, update.getCallbackQuery().getId());

        switch (callbackData) {
            case "FERTILIZING:DEFAULT" -> {
                createFertilizingSchedule(user, DEFAULT_FERTILIZING_DAYS);
                finishCreation(user, client);
            }

            case "FERTILIZING:CUSTOM" -> askForCustomInterval(user, client);

            case "FERTILIZING:SKIP" -> {
                log.info(
                        "User {} skipped fertilizing for plant {}",
                        user.getTelegramChatId(),
                        getPlantId(user)
                );
                finishCreation(user, client);
            }

            default -> log.warn("Unknown fertilizing callback: {}", callbackData);
        }
    }

    private void handleCustomIntervalInput(User user, Update update, TelegramClient client) {
        String text = update.getMessage().getText().trim();
        Long chatId = user.getTelegramChatId();

        Map<String, Object> stateData = user.getStateData();

        if (!"true".equals(stateData.get("fertilizing_awaiting_input"))) {
            return;
        }

        try {
            int interval = Integer.parseInt(text);

            if (!PlantService.isValidInterval(interval)) {
                sendText(
                        client,
                        chatId,
                        "❌ Интервал должен быть от 1 до 365 дней. Попробуй ещё раз:"
                );
                return;
            }

            userService.setStateData(user, "fertilizing_awaiting_input", null);

            createFertilizingSchedule(user, interval);
            finishCreation(user, client);

        } catch (NumberFormatException e) {
            sendText(client, chatId, "❌ Введи число от 1 до 365, пожалуйста.");
        }
    }

    private void askForCustomInterval(User user, TelegramClient client) {
        userService.setStateData(user, "fertilizing_awaiting_input", "true");

        sendText(
                client,
                user.getTelegramChatId(),
                "📅 Введи интервал удобрения в днях от 1 до 365.\n\n" +
                        "Например: 14 — раз в две недели"
        );
    }

    private void createFertilizingSchedule(User user, int intervalDays) {
        Long plantId = getPlantId(user);

        Plant plant = plantRepository.findById(plantId).orElse(null);

        if (plant == null) {
            log.error(
                    "Plant {} not found for user {} during fertilizing setup",
                    plantId,
                    user.getTelegramChatId()
            );
            return;
        }

        LocalDateTime nextDueAt = LocalDateTime.now()
                .truncatedTo(ChronoUnit.MICROS)
                .plusDays(intervalDays);

        plantService.addCareSchedule(
                plant,
                TaskType.FERTILIZING,
                intervalDays,
                nextDueAt
        );

        log.info(
                "Fertilizing schedule created: plant={}, interval={}",
                plantId,
                intervalDays
        );
    }

    private void finishCreation(User user, TelegramClient client) {
        Long plantId = getPlantId(user);

        Plant plant = plantRepository.findById(plantId).orElse(null);
        String plantName = plant != null ? plant.getName() : "растение";

        List<CareSchedule> schedules = plant != null
                ? plantService.getActiveSchedules(plantId)
                : List.of();

        String text = buildSummary(plantName, schedules);

        try {
            client.execute(SendMessage.builder()
                    .chatId(user.getTelegramChatId().toString())
                    .text(text)
                    .parseMode("Markdown")
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send creation summary", e);
        }

        userService.resetToIdle(user);

        mainMenuService.sendMainMenu(user, client);

        log.info(
                "Plant creation completed for user {}, plant={}",
                user.getTelegramChatId(),
                plantId
        );
    }

    private String buildSummary(String plantName, List<CareSchedule> schedules) {
        StringBuilder sb = new StringBuilder();

        sb.append("✅ *").append(plantName).append("* добавлена!\n\n");

        List<String> lines = new ArrayList<>();

        for (CareSchedule schedule : schedules) {
            String icon = switch (schedule.getTaskType()) {
                case WATERING -> "💧 Полив";
                case MISTING -> "💨 Опрыскивание";
                case FERTILIZING -> "🌿 Удобрение";
            };

            long daysUntil = Duration.between(
                    LocalDateTime.now().truncatedTo(ChronoUnit.DAYS),
                    schedule.getNextDueAt().truncatedTo(ChronoUnit.DAYS)
            ).toDays();

            String when = daysUntil <= 0
                    ? "сегодня"
                    : daysUntil == 1
                      ? "завтра"
                      : "через " + daysUntil + " " + pluralDays(daysUntil);

            lines.add(icon + " — " + when + " (" + schedule.getNextDueAt().format(DATE_FMT) + ")");
        }

        if (lines.isEmpty()) {
            sb.append("Расписание не настроено.");
        } else {
            sb.append(String.join("\n", lines));
        }

        sb.append("\n\nБуду напоминать вовремя 🌱");

        return sb.toString();
    }

    private String pluralDays(long n) {
        if (n % 10 == 1 && n % 100 != 11) {
            return "день";
        }

        if (n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 10 || n % 100 >= 20)) {
            return "дня";
        }

        return "дней";
    }

    private Long getPlantId(User user) {
        Object raw = user.getStateData().get("plant_id");

        if (raw == null) {
            throw new IllegalStateException("plant_id missing from stateData");
        }

        return Long.parseLong(raw.toString());
    }

    private void sendText(TelegramClient client, Long chatId, String text) {
        try {
            client.execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send message", e);
        }
    }

    private void answerCallback(TelegramClient client, String callbackId) {
        try {
            client.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .build());
        } catch (Exception e) {
            log.error("Failed to answer callback", e);
        }
    }
}
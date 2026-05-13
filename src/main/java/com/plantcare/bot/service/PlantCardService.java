package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.PlantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Единое место рендера карточки растения.
 *
 * Поддерживает два режима:
 *  1) "Карточка после создания" — финал визарда добавления. Картинку отдаём
 *     через sendPhoto + caption, текст — обычным sendMessage.
 *  2) "Детальный просмотр" (issue #26) — карточка с действиями (полив, опрыскивание,
 *     удобрение, фото, настройки). Всегда EditMessageText в одном и том же
 *     сообщении, чтобы не плодить сообщения в чате при навигации список ↔ карточка
 *     ↔ обновление после быстрой отметки ухода.
 *
 * Фото в детальной карточке выводится отдельным sendPhoto по кнопке "📷 Фото",
 * чтобы карточка оставалась обычным текстовым сообщением и могла редактироваться
 * через EditMessageText (с медиа-сообщениями такое не работает — Telegram не
 * даёт редактировать text → media и обратно).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlantCardService {

    /** Лимит caption у sendPhoto — 1024 символа (Bot API). */
    private static final int CAPTION_MAX_LENGTH = 1024;

    /** Источник возврата по умолчанию — список "Мои растения". */
    public static final String BACK_TO_LIST = "LIST";

    /** Префикс back-токена для возврата в комнату: LOC:<locationId>. */
    public static final String BACK_TO_LOCATION_PREFIX = "LOC:";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM");

    private final PlantService plantService;
    private final PlantRepository plantRepository;
    private final MainMenuService mainMenuService;
    private final UserService userService;

    // =================================================================
    // 1) Карточка "только что создано" — используется визардом создания
    // =================================================================

    /**
     * Финальный шаг создания растения:
     *   1) показать карточку (с фото или без)
     *   2) сбросить пользователя в IDLE и очистить stateData
     *   3) показать главное меню
     */
    @Transactional
    public void finishPlantCreation(User user, Plant plant, TelegramClient client) {
        sendCreatedPlantCard(user, plant, client);

        userService.resetToIdle(user);
        mainMenuService.sendMainMenu(user, client);

        log.info(
                "Plant creation completed for user {}, plant={}",
                user.getTelegramChatId(),
                plant.getId()
        );
    }

    /**
     * Шлёт карточку только что созданного растения.
     * Если фото загружено — sendPhoto с подписью, иначе sendMessage.
     */
    public void sendCreatedPlantCard(User user, Plant plant, TelegramClient client) {
        List<CareSchedule> schedules = plantService.getActiveSchedules(plant.getId());
        String caption = buildCreatedCardText(plant, schedules);

        if (plant.getPhotoFileId() != null && !plant.getPhotoFileId().isBlank()) {
            sendPhotoMessage(user.getTelegramChatId(), plant.getPhotoFileId(), caption, client);
        } else {
            sendTextMessage(user.getTelegramChatId(), caption, client);
        }
    }

    private String buildCreatedCardText(Plant plant, List<CareSchedule> schedules) {
        StringBuilder text = new StringBuilder();

        text.append("✅ Растение добавлено!\n\n");
        text.append("🌿 *").append(escapeMd(plant.getName())).append("*\n");

        if (plant.getLocation() != null) {
            text.append("📍 Комната: ")
                    .append(escapeMd(plant.getLocation().getDisplayName()))
                    .append("\n");
        }

        if (plant.getPhotoFileId() == null || plant.getPhotoFileId().isBlank()) {
            text.append("📷 Фото: не загружено\n");
        }

        if (!schedules.isEmpty()) {
            text.append("\n📅 Напоминания:\n");

            for (CareSchedule schedule : schedules) {
                text.append("• ")
                        .append(taskName(schedule.getTaskType()))
                        .append(" каждые ")
                        .append(schedule.getIntervalDays())
                        .append(" дн.\n");
            }
        }

        return text.toString();
    }

    // =================================================================
    // 2) Детальная карточка для просмотра (issue #26)
    // =================================================================

    /**
     * Отрисовать детальную карточку растения.
     *
     * @param user       владелец растения (для chatId и валидации владения)
     * @param plantId    ID растения
     * @param messageId  если не null — карточка редактируется в этом сообщении
     *                   (для бесшовного перехода список ↔ карточка). Если null —
     *                   отправляется новым сообщением.
     * @param backTarget куда вести по кнопке "Назад":
     *                     - {@link #BACK_TO_LIST} — к списку «Мои растения»
     *                     - {@link #BACK_TO_LOCATION_PREFIX} + locationId — к комнате
     * @param client     telegram client
     */
    @Transactional(readOnly = true)
    public void showPlantCard(
            User user,
            Long plantId,
            Integer messageId,
            String backTarget,
            TelegramClient client
    ) {
        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .orElse(null);

        if (plant == null) {
            log.warn("Plant {} not found for user {}", plantId, user.getTelegramChatId());
            sendTextMessage(
                    user.getTelegramChatId(),
                    "❌ Растение не найдено. Возможно, оно было удалено.",
                    client
            );
            return;
        }

        // Принудительно инициализируем lazy-связи, которые понадобятся ниже.
        Location location = plant.getLocation();
        if (location != null) {
            location.getName();
            location.getEmoji();
        }

        List<CareSchedule> schedules = plantService.getActiveSchedules(plant.getId());

        String text = buildDetailedCardText(plant, schedules);
        InlineKeyboardMarkup keyboard = buildDetailedCardKeyboard(plant, schedules, backTarget);

        sendOrEditText(user.getTelegramChatId(), messageId, text, keyboard, client);
    }

    /**
     * Отрисовать "карточку настроек" растения (расширение карточки в режиме редактирования).
     * Сейчас это заглушка с одной полезной кнопкой (переместить) — оставляем её
     * именно тут, чтобы edit-mode из других задач мог легко её дополнить.
     */
    @Transactional(readOnly = true)
    public void showSettingsScreen(
            User user,
            Long plantId,
            Integer messageId,
            String backTarget,
            TelegramClient client
    ) {
        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .orElse(null);

        if (plant == null) {
            sendTextMessage(user.getTelegramChatId(),
                    "❌ Растение не найдено.", client);
            return;
        }

        String text = "⚙️ *Настройки растения*\n\n🌿 " + escapeMd(plant.getName())
                + "\n\n_Полный режим редактирования появится в следующих обновлениях._";

        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("📦 Переместить в другую комнату")
                        .callbackData("PLANT:MOVE:" + plant.getId())
                        .build()
        )));
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ К карточке")
                        .callbackData(plantCardCallback(plant.getId(), backTarget))
                        .build()
        )));

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder().keyboard(rows).build();
        sendOrEditText(user.getTelegramChatId(), messageId, text, keyboard, client);
    }

    /**
     * Отправить фото растения отдельным сообщением (по кнопке "📷 Фото").
     * Карточка остаётся прежней, фото просто "выскакивает" под ней.
     * Если фото нет — отвечаем callback'ом без отдельного сообщения.
     */
    @Transactional(readOnly = true)
    public void sendPlantPhoto(User user, Long plantId, String callbackId, TelegramClient client) {
        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .orElse(null);

        if (plant == null) {
            answerCallback(client, callbackId, "❌ Растение не найдено");
            return;
        }

        if (plant.getPhotoFileId() == null || plant.getPhotoFileId().isBlank()) {
            answerCallback(client, callbackId, "Фото ещё не загружено");
            return;
        }

        SendPhoto photo = SendPhoto.builder()
                .chatId(user.getTelegramChatId().toString())
                .photo(new InputFile(plant.getPhotoFileId()))
                .caption("🌿 " + plant.getName())
                .build();

        try {
            client.execute(photo);
            answerCallback(client, callbackId, "");
        } catch (TelegramApiException e) {
            log.error("Failed to send plant photo (plant={}): {}", plant.getId(), e.getMessage());
            answerCallback(client, callbackId, "❌ Не удалось отправить фото");
        }
    }

    // =================================================================
    // Рендер карточки
    // =================================================================

    private String buildDetailedCardText(Plant plant, List<CareSchedule> schedules) {
        StringBuilder sb = new StringBuilder();

        sb.append("🌿 *").append(escapeMd(plant.getName())).append("*\n");

        if (plant.getLocation() != null) {
            sb.append("📍 ").append(escapeMd(plant.getLocation().getDisplayName())).append("\n");
        }

        if (plant.getPhotoFileId() != null && !plant.getPhotoFileId().isBlank()) {
            sb.append("📷 Фото загружено\n");
        }

        if (plant.getNotes() != null && !plant.getNotes().isBlank()) {
            sb.append("\n📝 _").append(escapeMd(plant.getNotes().trim())).append("_\n");
        }

        if (schedules.isEmpty()) {
            sb.append("\n📅 Расписание ухода не настроено.");
            return sb.toString();
        }

        sb.append("\n📅 *Ближайший уход:*\n");

        // Сортируем для стабильного порядка: WATERING, MISTING, FERTILIZING.
        List<CareSchedule> sorted = schedules.stream()
                .sorted(Comparator.comparing(s -> s.getTaskType().ordinal()))
                .toList();

        LocalDate today = LocalDate.now();

        for (CareSchedule schedule : sorted) {
            LocalDate due = schedule.getNextDueAt().toLocalDate();
            String dueLabel;

            if (due.isBefore(today)) {
                long overdueDays = today.toEpochDay() - due.toEpochDay();
                dueLabel = "⚠️ просрочено на " + overdueDays + " дн.";
            } else if (due.equals(today)) {
                dueLabel = "сегодня";
            } else if (due.equals(today.plusDays(1))) {
                dueLabel = "завтра";
            } else {
                dueLabel = due.format(DATE_FMT);
            }

            sb.append(taskEmoji(schedule.getTaskType()))
                    .append(" ").append(taskName(schedule.getTaskType()))
                    .append(" — ").append(dueLabel).append("\n");
        }

        return sb.toString();
    }

    private InlineKeyboardMarkup buildDetailedCardKeyboard(
            Plant plant,
            List<CareSchedule> schedules,
            String backTarget
    ) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        // 1) Кнопки быстрой отметки — только для активных расписаний.
        if (!schedules.isEmpty()) {
            InlineKeyboardRow careRow = new InlineKeyboardRow();
            List<CareSchedule> sorted = schedules.stream()
                    .sorted(Comparator.comparing(s -> s.getTaskType().ordinal()))
                    .toList();

            for (CareSchedule schedule : sorted) {
                careRow.add(InlineKeyboardButton.builder()
                        .text(taskEmoji(schedule.getTaskType()) + " " + doneVerb(schedule.getTaskType()))
                        .callbackData("PLANT:CARE:" + plant.getId() + ":" + schedule.getTaskType().name())
                        .build());
            }
            rows.add(careRow);
        }

        // 2) Вспомогательные действия: Фото (если есть), Настройки.
        InlineKeyboardRow auxRow = new InlineKeyboardRow();
        if (plant.getPhotoFileId() != null && !plant.getPhotoFileId().isBlank()) {
            auxRow.add(InlineKeyboardButton.builder()
                    .text("📷 Фото")
                    .callbackData("PLANT:PHOTO:" + plant.getId())
                    .build());
        }
        auxRow.add(InlineKeyboardButton.builder()
                .text("⚙️ Настройки")
                .callbackData(plantSettingsCallback(plant.getId(), backTarget))
                .build());
        rows.add(auxRow);

        // 3) Назад.
        rows.add(new InlineKeyboardRow(List.of(buildBackButton(backTarget))));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private InlineKeyboardButton buildBackButton(String backTarget) {
        if (backTarget != null && backTarget.startsWith(BACK_TO_LOCATION_PREFIX)) {
            String locationId = backTarget.substring(BACK_TO_LOCATION_PREFIX.length());
            return InlineKeyboardButton.builder()
                    .text("⬅️ К комнате")
                    .callbackData("LOCATION:VIEW:" + locationId)
                    .build();
        }

        return InlineKeyboardButton.builder()
                .text("⬅️ К списку")
                .callbackData("PLANT:LIST")
                .build();
    }

    /**
     * Callback для возврата к карточке с сохранением back-контекста.
     * Используется кнопкой "К карточке" со страницы Настроек.
     */
    private String plantCardCallback(Long plantId, String backTarget) {
        if (backTarget != null && backTarget.startsWith(BACK_TO_LOCATION_PREFIX)) {
            return "PLANT:VIEW:" + plantId + ":" + backTarget;
        }
        return "PLANT:VIEW:" + plantId;
    }

    /**
     * Callback для перехода в Настройки с сохранением back-контекста.
     */
    private String plantSettingsCallback(Long plantId, String backTarget) {
        if (backTarget != null && backTarget.startsWith(BACK_TO_LOCATION_PREFIX)) {
            return "PLANT:SETTINGS:" + plantId + ":" + backTarget;
        }
        return "PLANT:SETTINGS:" + plantId;
    }

    // =================================================================
    // Хелперы низкоуровневой отправки
    // =================================================================

    private void sendOrEditText(
            Long chatId,
            Integer messageId,
            String text,
            InlineKeyboardMarkup keyboard,
            TelegramClient client
    ) {
        if (messageId != null) {
            EditMessageText edit = EditMessageText.builder()
                    .chatId(chatId.toString())
                    .messageId(messageId)
                    .text(text)
                    .parseMode("Markdown")
                    .replyMarkup(keyboard)
                    .build();
            try {
                client.execute(edit);
                return;
            } catch (TelegramApiException e) {
                // Самый частый случай — "message is not modified". Тогда тихо игнорируем.
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("message is not modified")) {
                    log.debug("Plant card already up-to-date for chat {}", chatId);
                    return;
                }
                log.warn("Failed to edit plant card (chat={}, msg={}), falling back to send: {}",
                        chatId, messageId, e.getMessage());
                // Fallback: если редактирование не вышло (например, удалили сообщение)
                // — присылаем новым.
            }
        }

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("Markdown")
                .replyMarkup(keyboard)
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send plant card (chat={}): {}", chatId, e.getMessage());
        }
    }

    private void sendTextMessage(Long chatId, String text, TelegramClient client) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("Markdown")
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send plant text message (chatId={})", chatId, e);
        }
    }

    private void sendPhotoMessage(Long chatId, String fileId, String caption, TelegramClient client) {
        SendPhoto photo = SendPhoto.builder()
                .chatId(chatId.toString())
                .photo(new InputFile(fileId))
                .caption(truncate(caption, CAPTION_MAX_LENGTH))
                .parseMode("Markdown")
                .build();

        try {
            client.execute(photo);
        } catch (TelegramApiException e) {
            log.error("Failed to send plant photo card (chatId={}), fallback to text", chatId, e);
            sendTextMessage(chatId, caption, client);
        }
    }

    private void answerCallback(TelegramClient client, String callbackId, String text) {
        if (callbackId == null) {
            return;
        }
        try {
            client.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Failed to answer callback {}: {}", callbackId, e.getMessage());
        }
    }

    // =================================================================
    // Утилиты форматирования
    // =================================================================

    private String taskName(TaskType taskType) {
        return switch (taskType) {
            case WATERING -> "Полив";
            case MISTING -> "Опрыскивание";
            case FERTILIZING -> "Удобрение";
        };
    }

    private String taskEmoji(TaskType taskType) {
        return switch (taskType) {
            case WATERING -> "💧";
            case MISTING -> "💨";
            case FERTILIZING -> "🌿";
        };
    }

    /**
     * Глагол прошедшего времени для кнопки отметки выполнения: «Полил», «Опрыскал», «Удобрил».
     */
    private String doneVerb(TaskType taskType) {
        return switch (taskType) {
            case WATERING -> "Полил";
            case MISTING -> "Опрыскал";
            case FERTILIZING -> "Удобрил";
        };
    }

    /**
     * Минимальное экранирование символов, ломающих Markdown-разметку Telegram.
     * Заметки/имена могут содержать «*», «_», «[» и т.п., из-за которых сообщение
     * не отправится с parse_mode=Markdown.
     */
    private String escapeMd(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("`", "\\`");
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 1) + "…";
    }
}

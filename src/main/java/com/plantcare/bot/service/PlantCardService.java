package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareHistory;
import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.PlantEvent;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.PlantEventType;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.PlantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
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
    private static final DateTimeFormatter HISTORY_DATE_FMT = DateTimeFormatter.ofPattern("dd.MM");

    private final PlantService plantService;
    private final PlantRepository plantRepository;
    private final MainMenuService mainMenuService;
    private final UserService userService;
    private final CareHistoryService careHistoryService;
    private final PlantEventService plantEventService;

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

        List<CareSchedule> schedules = plantService.getActiveSchedules(plant.getId());

        StringBuilder text = new StringBuilder();
        text.append("⚙️ *Настройки растения*\n\n");
        text.append("🌿 ").append(escapeMd(plant.getName())).append("\n");
        if (plant.getLocation() != null) {
            text.append("📍 ").append(escapeMd(plant.getLocation().getDisplayName())).append("\n");
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("✏️ Переименовать")
                        .callbackData("PLANT:EDIT:NAME:" + plant.getId() + backSuffix(backTarget))
                        .build()
        )));
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("📝 Заметка")
                        .callbackData("PLANT:EDIT:NOTE:" + plant.getId() + backSuffix(backTarget))
                        .build(),
                InlineKeyboardButton.builder()
                        .text("📷 Фото")
                        .callbackData("PLANT:EDIT:PHOTO:" + plant.getId() + backSuffix(backTarget))
                        .build()
        )));
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("📦 Переместить")
                        .callbackData("PLANT:MOVE:" + plant.getId())
                        .build()
        )));
        // issue #68: сохранение растения как шаблон
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("💾 Сохранить как шаблон")
                        .callbackData("PLANT:SAVE_TPL:" + plant.getId() + backSuffix(backTarget))
                        .build()
        )));

        // Расписания — если есть хотя бы одно активное, даём «Ближайшее».
        // Управление вкл/выкл всех трёх — отдельной страницей.
        if (!schedules.isEmpty()) {
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text("⏰ Ближайшее напоминание")
                            .callbackData("PLANT:SCHED:NEAREST:" + plant.getId() + backSuffix(backTarget))
                            .build()
            )));
        }
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("🔔 Типы ухода")
                        .callbackData("PLANT:CARE_TYPES:" + plant.getId() + backSuffix(backTarget))
                        .build()
        )));

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("🗑 Удалить растение")
                        .callbackData("PLANT:EDIT:DELETE:" + plant.getId() + backSuffix(backTarget))
                        .build()
        )));

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ К карточке")
                        .callbackData(plantCardCallback(plant.getId(), backTarget))
                        .build()
        )));

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder().keyboard(rows).build();
        sendOrEditText(user.getTelegramChatId(), messageId, text.toString(), keyboard, client);
    }

    /**
     * Экран редактирования ближайшего напоминания (одного, того, что наступит первым).
     */
    @Transactional(readOnly = true)
    public void showNearestScheduleScreen(
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

        CareSchedule nearest = plantService.getActiveSchedules(plant.getId()).stream()
                .min(Comparator.comparing(CareSchedule::getNextDueAt))
                .orElse(null);

        if (nearest == null) {
            // Нет активных расписаний — отправляем на страницу типов ухода
            showCareTypesScreen(user, plantId, messageId, backTarget, client);
            return;
        }

        showScheduleEditScreen(user, plant, nearest, messageId, backTarget, client);
    }

    /**
     * Экран редактирования конкретного типа ухода (для случая, когда юзер пришёл
     * не из «ближайшее», а из таблицы типов).
     */
    @Transactional(readOnly = true)
    public void showScheduleEditByType(
            User user,
            Long plantId,
            TaskType taskType,
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

        CareSchedule schedule = plantService.getActiveSchedules(plant.getId()).stream()
                .filter(s -> s.getTaskType() == taskType)
                .findFirst()
                .orElse(null);

        if (schedule == null) {
            // Расписание этого типа неактивно/не существует — возвращаем на страницу типов
            showCareTypesScreen(user, plantId, messageId, backTarget, client);
            return;
        }

        showScheduleEditScreen(user, plant, schedule, messageId, backTarget, client);
    }

    private void showScheduleEditScreen(
            User user,
            Plant plant,
            CareSchedule schedule,
            Integer messageId,
            String backTarget,
            TelegramClient client
    ) {
        StringBuilder text = new StringBuilder();
        text.append(taskEmoji(schedule.getTaskType())).append(" *")
                .append(taskName(schedule.getTaskType())).append("*\n\n");
        text.append("🌿 ").append(escapeMd(plant.getName())).append("\n");
        text.append("📅 Каждые ").append(schedule.getIntervalDays()).append(" дн.\n");
        text.append("⏰ Следующий: ")
                .append(schedule.getNextDueAt().toLocalDate().format(DATE_FMT))
                .append("\n");

        String taskCode = schedule.getTaskType().name();
        String back = backSuffix(backTarget);

        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("📅 Изменить интервал")
                        .callbackData("PLANT:SCHED:INTERVAL:" + plant.getId() + ":" + taskCode + back)
                        .build()
        )));
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⏭ Сегодня")
                        .callbackData("PLANT:SCHED:POSTPONE:" + plant.getId() + ":" + taskCode + ":0" + back)
                        .build(),
                InlineKeyboardButton.builder()
                        .text("⏭ Завтра")
                        .callbackData("PLANT:SCHED:POSTPONE:" + plant.getId() + ":" + taskCode + ":1" + back)
                        .build()
        )));
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⏭ +3 дня")
                        .callbackData("PLANT:SCHED:POSTPONE:" + plant.getId() + ":" + taskCode + ":3" + back)
                        .build(),
                InlineKeyboardButton.builder()
                        .text("⏭ +7 дней")
                        .callbackData("PLANT:SCHED:POSTPONE:" + plant.getId() + ":" + taskCode + ":7" + back)
                        .build()
        )));
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ К настройкам")
                        .callbackData("PLANT:SETTINGS:" + plant.getId() + backSuffix(backTarget))
                        .build()
        )));

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder().keyboard(rows).build();
        sendOrEditText(user.getTelegramChatId(), messageId, text.toString(), keyboard, client);
    }

    /**
     * Экран управления типами ухода: вкл/выкл каждого из трёх (включая создание расписания,
     * если его раньше не было).
     */
    @Transactional(readOnly = true)
    public void showCareTypesScreen(
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

        // Карта существующих расписаний по типу.
        java.util.Map<TaskType, CareSchedule> byType = new java.util.EnumMap<>(TaskType.class);
        for (CareSchedule s : plantService.getAllSchedules(plant.getId())) {
            byType.put(s.getTaskType(), s);
        }

        StringBuilder text = new StringBuilder();
        text.append("🔔 *Типы ухода*\n\n");
        text.append("🌿 ").append(escapeMd(plant.getName())).append("\n\n");

        for (TaskType type : TaskType.values()) {
            CareSchedule s = byType.get(type);
            text.append(taskEmoji(type)).append(" ").append(taskName(type)).append(": ");
            if (s != null && s.isActive()) {
                text.append("✅ каждые ").append(s.getIntervalDays()).append(" дн.\n");
            } else if (s != null) {
                text.append("❌ выключено (было каждые ").append(s.getIntervalDays()).append(" дн.)\n");
            } else {
                text.append("➖ не настроено\n");
            }
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (TaskType type : TaskType.values()) {
            CareSchedule s = byType.get(type);
            boolean activeNow = s != null && s.isActive();
            String label = (activeNow ? "❌ Выключить " : "✅ Включить ") + taskName(type);
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text(label)
                            .callbackData("PLANT:SCHED:TOGGLE:" + plant.getId() + ":" + type.name()
                                    + backSuffix(backTarget))
                            .build()
            )));
        }

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ К настройкам")
                        .callbackData("PLANT:SETTINGS:" + plant.getId() + backSuffix(backTarget))
                        .build()
        )));

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder().keyboard(rows).build();
        sendOrEditText(user.getTelegramChatId(), messageId, text.toString(), keyboard, client);
    }

    /**
     * Экран подтверждения удаления (архивирования) растения.
     */
    @Transactional(readOnly = true)
    public void showDeleteConfirmScreen(
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

        String text = "🗑 *Удалить растение?*\n\n"
                + "🌿 " + escapeMd(plant.getName()) + "\n\n"
                + "_Все его напоминания тоже отключатся._";

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("✅ Да, удалить")
                                .callbackData("PLANT:EDIT:DELETE_CONFIRM:" + plant.getId())
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("⬅️ Отмена")
                                .callbackData("PLANT:SETTINGS:" + plant.getId() + backSuffix(backTarget))
                                .build()
                )))
                .build();

        sendOrEditText(user.getTelegramChatId(), messageId, text, keyboard, client);
    }

    /**
     * Сообщение с подсказкой "введи новое имя" + кнопка отмены.
     * Возвращает messageId настроек, которое нужно сохранить в stateData,
     * чтобы по завершении ввода вернуться именно в него.
     */
    public void promptForNewName(User user, Long plantId, Integer settingsMessageId,
                                 String backTarget, TelegramClient client) {
        String text = "✏️ Введи новое имя растения.\n\n"
                + "Например: «Монстера у окна»\n\n"
                + "_От 1 до 100 символов. /cancel — отменить._";

        InlineKeyboardMarkup keyboard = cancelEditKeyboard(plantId, settingsMessageId, backTarget);
        sendTextWithKeyboard(user.getTelegramChatId(), text, keyboard, client);
    }

    public void promptForNote(User user, Plant plant, Integer settingsMessageId,
                              String backTarget, TelegramClient client) {
        StringBuilder text = new StringBuilder();
        text.append("📝 Введи новую заметку для *").append(escapeMd(plant.getName())).append("*.\n\n");
        if (plant.getNotes() != null && !plant.getNotes().isBlank()) {
            text.append("_Сейчас: ").append(escapeMd(plant.getNotes())).append("_\n\n");
        }
        text.append("До ").append(PlantService.NOTE_MAX_LENGTH)
                .append(" символов. /cancel — отменить.");

        List<InlineKeyboardRow> rows = new ArrayList<>();
        if (plant.getNotes() != null && !plant.getNotes().isBlank()) {
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text("🗑 Очистить заметку")
                            .callbackData("PLANT:EDIT:NOTE_CLEAR:" + plant.getId() + backSuffix(backTarget))
                            .build()
            )));
        }
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("Отмена")
                        .callbackData("PLANT:SETTINGS:" + plant.getId() + backSuffix(backTarget))
                        .build()
        )));

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder().keyboard(rows).build();
        sendMessageWithMarkdownAndKeyboard(user.getTelegramChatId(), text.toString(), keyboard, client);
    }

    public void promptForPhotoEdit(User user, Long plantId, Integer settingsMessageId,
                                   String backTarget, TelegramClient client) {
        String text = "📷 Пришли новое фото растения.\n\n"
                + "_Старое фото будет заменено. /cancel — отменить._";

        InlineKeyboardMarkup keyboard = cancelEditKeyboard(plantId, settingsMessageId, backTarget);
        sendTextWithKeyboard(user.getTelegramChatId(), text, keyboard, client);
    }

    public void promptForNewInterval(User user, Long plantId, TaskType taskType,
                                     Integer settingsMessageId, String backTarget,
                                     TelegramClient client) {
        String text = "📅 Введи новый интервал для "
                + taskEmoji(taskType) + " " + taskName(taskType).toLowerCase()
                + " в днях (от 1 до 365).\n\n"
                + "Например: 7 — раз в неделю.\n\n"
                + "_/cancel — отменить._";

        InlineKeyboardMarkup keyboard = cancelEditKeyboard(plantId, settingsMessageId, backTarget);
        sendTextWithKeyboard(user.getTelegramChatId(), text, keyboard, client);
    }

    private InlineKeyboardMarkup cancelEditKeyboard(Long plantId, Integer settingsMessageId,
                                                    String backTarget) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("Отмена")
                                .callbackData("PLANT:SETTINGS:" + plantId + backSuffix(backTarget))
                                .build()
                )))
                .build();
    }

    private void sendTextWithKeyboard(Long chatId, String text, InlineKeyboardMarkup keyboard,
                                      TelegramClient client) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .replyMarkup(keyboard)
                .build();
        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send prompt message", e);
        }
    }

    private void sendMessageWithMarkdownAndKeyboard(Long chatId, String text,
                                                    InlineKeyboardMarkup keyboard,
                                                    TelegramClient client) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("Markdown")
                .replyMarkup(keyboard)
                .build();
        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send markdown prompt message", e);
        }
    }

    /**
     * Хвост callback-data для сохранения back-контекста: пусто или ":LOC:<id>".
     */
    private String backSuffix(String backTarget) {
        if (backTarget != null && backTarget.startsWith(BACK_TO_LOCATION_PREFIX)) {
            return ":" + backTarget;
        }
        return "";
    }

    /**
     * Отправить фото растения отдельным сообщением (по кнопке "📷 Фото").
     * Карточка остаётся прежней, фото просто "выскакивает" под ней.
     * Если фото нет — отвечаем callback'ом без отдельного сообщения.
     *
     * @return {@code true}, если фото реально ушло в чат — вызывающий код может
     *         использовать этот сигнал, чтобы дослать пользователю свежую
     *         карточку растения после фото (issue: после просмотра фото
     *         юзеру нужно вернуть меню вниз чата, чтобы продолжить).
     *         {@code false} — фото не было отправлено (растение не найдено,
     *         file_id пуст, или Telegram API ошибка). В этом случае дополнительные
     *         сообщения слать не нужно — alert callback'а уже достаточен.
     */
    @Transactional(readOnly = true)
    public boolean sendPlantPhoto(User user, Long plantId, String callbackId, TelegramClient client) {
        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .orElse(null);

        if (plant == null) {
            answerCallback(client, callbackId, "❌ Растение не найдено");
            return false;
        }

        if (plant.getPhotoFileId() == null || plant.getPhotoFileId().isBlank()) {
            answerCallback(client, callbackId, "Фото ещё не загружено");
            return false;
        }

        SendPhoto photo = SendPhoto.builder()
                .chatId(user.getTelegramChatId().toString())
                .photo(new InputFile(plant.getPhotoFileId()))
                .caption("🌿 " + plant.getName())
                .build();

        try {
            client.execute(photo);
            answerCallback(client, callbackId, "");
            return true;
        } catch (TelegramApiException e) {
            log.error("Failed to send plant photo (plant={}): {}", plant.getId(), e.getMessage());
            answerCallback(client, callbackId, "❌ Не удалось отправить фото");
            return false;
        }
    }

    // =================================================================
    // 3) Экран «📜 История» (issue #51)
    // =================================================================

    /**
     * Отрисовать экран истории ухода для растения с пагинацией.
     *
     * @param page        страница (0-based)
     */
    @Transactional(readOnly = true)
    public void showHistoryScreen(
            User user,
            Long plantId,
            int page,
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

        // Принудительно подтягиваем lazy-локацию для шапки.
        if (plant.getLocation() != null) {
            plant.getLocation().getName();
        }

        long total = careHistoryService.countHistory(plantId);
        int pageSize = CareHistoryService.HISTORY_PAGE_SIZE;
        int safePage = Math.max(0, page);
        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / pageSize);
        if (safePage >= totalPages) {
            safePage = totalPages - 1;
        }

        List<CareHistory> entries = careHistoryService.getHistoryPage(plantId, safePage);
        CareHistoryService.PlantStats stats = careHistoryService.getPlantStats(plantId);
        ZoneId tz = parseUserZone(user);

        String text = buildHistoryText(plant, entries, stats, safePage, totalPages, total, tz);
        InlineKeyboardMarkup keyboard = buildHistoryKeyboard(plant, safePage, totalPages, backTarget);

        sendOrEditText(user.getTelegramChatId(), messageId, text, keyboard, client);
    }

    private String buildHistoryText(
            Plant plant,
            List<CareHistory> entries,
            CareHistoryService.PlantStats stats,
            int page,
            int totalPages,
            long total,
            ZoneId tz
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("📜 *История ухода — ").append(escapeMd(plant.getName())).append("*\n\n");

        if (entries.isEmpty()) {
            sb.append("История пока пуста. Отметь первое действие в карточке 🌱");
            return sb.toString();
        }

        for (CareHistory h : entries) {
            sb.append(formatHistoryLine(h, tz)).append("\n");
        }

        sb.append("\n");

        if (!stats.hasEnoughData()) {
            sb.append("_Пока мало данных, продолжай ухаживать 🌱_");
        } else {
            sb.append("🔥 Стрик: ").append(stats.streak()).append(" выполнений подряд\n");
            sb.append("✅ Вовремя: ").append(stats.onTimePct()).append("% за 30 дней\n");
            sb.append("📊 Всего действий: ").append(stats.total());
        }

        if (totalPages > 1) {
            sb.append("\n\n_Страница ").append(page + 1).append(" из ").append(totalPages).append("_");
        }
        return sb.toString();
    }

    // package-private для unit-теста (PlantCardServiceWateringHistoryTest)
    String formatHistoryLine(CareHistory h, ZoneId tz) {
        LocalDate doneDay = h.getDoneAt()
                .atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(tz)
                .toLocalDate();
        String date = doneDay.format(HISTORY_DATE_FMT);
        String emoji = taskEmoji(h.getTaskType());
        String verb = doneVerb(h.getTaskType()).toLowerCase();

        String status;
        if (h.isOnTime()) {
            status = "вовремя";
        } else {
            // Просрочка — посчитаем на сколько дней (от ожидаемого срока).
            // Здесь у нас нет CareSchedule, чтобы посчитать точно. Помечаем «с опозданием».
            status = "с опозданием";
        }

        // issue #71: для записей WATERING с заполненными деталями добавляем
        // обильность и сухость грунта. Старые записи (до V10) и не-WATERING типы
        // имеют was_abundant=NULL и soil_was_dry=NULL — для них формат прежний.
        String details = wateringDetailsSuffix(h);
        return date + " — " + emoji + " " + verb + details + " (" + status + ")";
    }

    /**
     * Строит фрагмент строки истории с обильностью + сухостью грунта (issue #71).
     * Возвращает пустую строку, если деталей нет (старая запись, bulk-полив,
     * MISTING/FERTILIZING).
     *
     * <p>Примеры:
     * <ul>
     *   <li>HEAVY + DRY → {@code ", обильно, земля сухая"}</li>
     *   <li>NORMAL + WET → {@code ", обычно, земля влажная"}</li>
     *   <li>HEAVY + UNKNOWN → {@code ", обильно"}</li>
     *   <li>оба null → {@code ""}</li>
     * </ul>
     */
    private String wateringDetailsSuffix(CareHistory h) {
        if (h.getTaskType() != TaskType.WATERING) {
            return "";
        }
        Boolean abundant = h.getWasAbundant();
        Boolean soilDry = h.getSoilWasDry();
        if (abundant == null && soilDry == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (abundant != null) {
            sb.append(", ").append(abundant ? "обильно" : "обычно");
        }
        if (soilDry != null) {
            sb.append(", земля ").append(soilDry ? "сухая" : "влажная");
        }
        return sb.toString();
    }

    private InlineKeyboardMarkup buildHistoryKeyboard(
            Plant plant,
            int page,
            int totalPages,
            String backTarget
    ) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        if (totalPages > 1) {
            InlineKeyboardRow nav = new InlineKeyboardRow();
            if (page > 0) {
                nav.add(InlineKeyboardButton.builder()
                        .text("← Назад")
                        .callbackData("PLANT:HISTORY:" + plant.getId() + ":" + (page - 1) + backSuffix(backTarget))
                        .build());
            }
            // Индикатор страницы — non-clickable, callback на текущую (no-op).
            nav.add(InlineKeyboardButton.builder()
                    .text((page + 1) + "/" + totalPages)
                    .callbackData("PLANT:HISTORY:" + plant.getId() + ":" + page + backSuffix(backTarget))
                    .build());
            if (page < totalPages - 1) {
                nav.add(InlineKeyboardButton.builder()
                        .text("Вперёд →")
                        .callbackData("PLANT:HISTORY:" + plant.getId() + ":" + (page + 1) + backSuffix(backTarget))
                        .build());
            }
            rows.add(nav);
        }

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ К карточке")
                        .callbackData(plantCardCallback(plant.getId(), backTarget))
                        .build()
        )));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private ZoneId parseUserZone(User user) {
        String tz = user.getTimezone();
        if (tz == null || tz.isBlank()) return ZoneOffset.UTC;
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            return ZoneOffset.UTC;
        }
    }

    // =================================================================
    // 3b) Журнал событий — выбор типа + просмотр списка (issue #76)
    // =================================================================

    /**
     * Меню выбора типа события. Заменяет текущее сообщение (карточку) на
     * вертикальный список из 4 типов + «Отмена».
     */
    @Transactional(readOnly = true)
    public void showEventTypeMenu(
            User user, Long plantId, Integer messageId, String backTarget, TelegramClient client
    ) {
        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .orElse(null);
        if (plant == null) {
            sendTextMessage(user.getTelegramChatId(), "❌ Растение не найдено.", client);
            return;
        }

        String text = "📝 *Добавить событие — " + escapeMd(plant.getName()) + "*\n\n"
                + "Выбери тип события:";

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (PlantEventType type : PlantEventType.values()) {
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text(eventEmoji(type) + " " + eventLabel(type))
                            .callbackData("PLANT:EVENT:SAVE:" + plantId + ":" + type.name()
                                    + backSuffix(backTarget))
                            .build()
            )));
        }
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ Отмена")
                        .callbackData(plantCardCallback(plantId, backTarget))
                        .build()
        )));

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder().keyboard(rows).build();
        sendOrEditText(user.getTelegramChatId(), messageId, text, keyboard, client);
    }

    /**
     * Журнал событий — постраничный список последних событий растения.
     * Пагинация: {@link PlantEventService#EVENTS_PAGE_SIZE} на страницу,
     * inline-кнопки {@code [← Назад] N/M [Вперёд →]}, если страниц больше одной.
     */
    public void showEventsScreen(
            User user, Long plantId, int page, Integer messageId, String backTarget,
            TelegramClient client
    ) {
        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .orElse(null);
        if (plant == null) {
            sendTextMessage(user.getTelegramChatId(), "❌ Растение не найдено.", client);
            return;
        }

        Page<PlantEvent> result = plantEventService.getEvents(user, plantId, page);
        int safePage = result.getNumber();
        int totalPages = Math.max(1, result.getTotalPages());

        String text = buildEventsText(plant, result.getContent(), safePage, totalPages);
        InlineKeyboardMarkup keyboard = buildEventsKeyboard(plant, safePage, totalPages, backTarget);

        sendOrEditText(user.getTelegramChatId(), messageId, text, keyboard, client);
    }

    private String buildEventsText(Plant plant, List<PlantEvent> events, int page, int totalPages) {
        StringBuilder sb = new StringBuilder();
        sb.append("📖 *Журнал событий — ").append(escapeMd(plant.getName())).append("*\n\n");

        if (events.isEmpty()) {
            sb.append("Здесь пока пусто. Нажми «📝 Добавить событие» в карточке, "
                    + "чтобы зафиксировать пересадку, обрезку или другое разовое действие.");
            return sb.toString();
        }

        for (PlantEvent e : events) {
            String date = e.getEventDate().toLocalDate().format(HISTORY_DATE_FMT);
            sb.append(date)
                    .append(" — ")
                    .append(eventEmoji(e.getEventType()))
                    .append(" ")
                    .append(eventLabel(e.getEventType()))
                    .append("\n");
        }

        if (totalPages > 1) {
            sb.append("\n_Страница ").append(page + 1).append(" из ").append(totalPages).append("_");
        }
        return sb.toString();
    }

    private InlineKeyboardMarkup buildEventsKeyboard(
            Plant plant, int page, int totalPages, String backTarget
    ) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        if (totalPages > 1) {
            InlineKeyboardRow nav = new InlineKeyboardRow();
            if (page > 0) {
                nav.add(InlineKeyboardButton.builder()
                        .text("← Назад")
                        .callbackData("PLANT:EVENT:LIST:" + plant.getId() + ":" + (page - 1)
                                + backSuffix(backTarget))
                        .build());
            }
            // Индикатор страницы — клик ведёт сам в себя (no-op rerender).
            nav.add(InlineKeyboardButton.builder()
                    .text((page + 1) + "/" + totalPages)
                    .callbackData("PLANT:EVENT:LIST:" + plant.getId() + ":" + page
                            + backSuffix(backTarget))
                    .build());
            if (page < totalPages - 1) {
                nav.add(InlineKeyboardButton.builder()
                        .text("Вперёд →")
                        .callbackData("PLANT:EVENT:LIST:" + plant.getId() + ":" + (page + 1)
                                + backSuffix(backTarget))
                        .build());
            }
            rows.add(nav);
        }

        // «Добавить событие» прямо отсюда — удобно, если юзер пришёл посмотреть
        // и решил тут же зафиксировать новое.
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("📝 Добавить событие")
                        .callbackData("PLANT:EVENT:ADD:" + plant.getId() + backSuffix(backTarget))
                        .build()
        )));

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ К карточке")
                        .callbackData(plantCardCallback(plant.getId(), backTarget))
                        .build()
        )));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private String eventLabel(PlantEventType type) {
        return switch (type) {
            case TRANSPLANT     -> "Пересадка / Смена горшка";
            case SOIL_CHANGE    -> "Замена грунта / Досыпка";
            case PRUNING        -> "Обрезка";
            case PEST_TREATMENT -> "Обработка от вредителей";
        };
    }

    private String eventEmoji(PlantEventType type) {
        return switch (type) {
            case TRANSPLANT     -> "🪴";
            case SOIL_CHANGE    -> "🌱";
            case PRUNING        -> "✂️";
            case PEST_TREATMENT -> "🐛";
        };
    }

    /**
     * Короткий локализованный label типа события — используется в alert'ах после
     * сохранения («Событие "Обрезка" сохранено в историю»). Без emoji, без слешей.
     */
    public String eventShortLabel(PlantEventType type) {
        return switch (type) {
            case TRANSPLANT     -> "Пересадка";
            case SOIL_CHANGE    -> "Замена грунта";
            case PRUNING        -> "Обрезка";
            case PEST_TREATMENT -> "Обработка от вредителей";
        };
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

        // 2) Вспомогательные действия: Фото (если есть), История, Настройки.
        InlineKeyboardRow auxRow = new InlineKeyboardRow();
        if (plant.getPhotoFileId() != null && !plant.getPhotoFileId().isBlank()) {
            auxRow.add(InlineKeyboardButton.builder()
                    .text("📷 Фото")
                    .callbackData("PLANT:PHOTO:" + plant.getId() + backSuffix(backTarget))
                    .build());
        }
        auxRow.add(InlineKeyboardButton.builder()
                .text("📜 История")
                .callbackData("PLANT:HISTORY:" + plant.getId() + ":0" + backSuffix(backTarget))
                .build());
        auxRow.add(InlineKeyboardButton.builder()
                .text("⚙️ Настройки")
                .callbackData(plantSettingsCallback(plant.getId(), backTarget))
                .build());
        rows.add(auxRow);

        // 2b) Журнал событий (issue #76): добавить событие + просмотр журнала.
        // Отдельный ряд, чтобы не смешивать с регулярным уходом и не перегружать auxRow.
        InlineKeyboardRow eventsRow = new InlineKeyboardRow();
        eventsRow.add(InlineKeyboardButton.builder()
                .text("📝 Добавить событие")
                .callbackData("PLANT:EVENT:ADD:" + plant.getId() + backSuffix(backTarget))
                .build());
        eventsRow.add(InlineKeyboardButton.builder()
                .text("📖 События")
                .callbackData("PLANT:EVENT:LIST:" + plant.getId() + ":0" + backSuffix(backTarget))
                .build());
        rows.add(eventsRow);

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
            case SOIL_CHECK -> "Проверка грунта";
        };
    }

    private String taskEmoji(TaskType taskType) {
        return switch (taskType) {
            case WATERING -> "💧";
            case MISTING -> "💨";
            case FERTILIZING -> "🌿";
            case SOIL_CHECK -> "🪴";
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
            case SOIL_CHECK -> "Проверил";
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

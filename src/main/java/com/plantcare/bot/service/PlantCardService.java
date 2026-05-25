package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareHistory;
import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.PlantEvent;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.domain.enums.PlantEventType;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareScheduleRepository;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.util.TimezoneSupport;
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
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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

    /**
     * Полный формат даты «15 марта 2024» — для строки «С тобой с…» в карточке
     * (issue #117). Locale = ru, чтобы месяц был на русском в любом окружении JVM.
     */
    private static final DateTimeFormatter ACQUIRED_DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.forLanguageTag("ru"));

    private final PlantService plantService;
    private final PlantRepository plantRepository;
    private final CareScheduleRepository careScheduleRepository;
    private final MainMenuService mainMenuService;
    private final UserService userService;
    private final CareHistoryService careHistoryService;
    private final HealthScoreService healthScoreService;
    private final PlantEventService plantEventService;
    private final SpeciesFactService speciesFactService;
    private final com.plantcare.bot.seasonal.service.SeasonalIntervalService seasonalIntervalService;

    // =================================================================
    // 1) Карточка "только что создано" — используется визардом создания
    // =================================================================


    /**
     * Создаёт растение-потомок (черенок) от материнского растения (issue #139, ADR-012).
     *
     * <p>Копирует как ШАБЛОН (ADR-005 — копирование значений, не ссылка на чужие
     * расписания): вид (species), локацию (location) и интервалы активных расписаний
     * ухода. Для каждого активного расписания родителя создаётся НОВОЕ
     * {@link CareSchedule} с тем же {@code intervalDays}; {@code nextDueAt}
     * отсчитывается от текущего момента + интервал (с учётом сезонной корректировки,
     * issue #67), потому что у нового растения свой график.
     *
     * <p>Проставляет {@code parent_id} (ссылку на родителя) и денормализованный
     * {@code user_id} — берётся от родителя/текущего юзера (ADR-010). Каскадной
     * архивации/удаления потомков нет (ADR-009).
     *
     * @param user       владелец (он же владелец родителя — проверяется при загрузке)
     * @param parentId   ID материнского растения
     * @param cuttingName имя нового растения-черенка
     * @return сохранённый потомок с расписаниями
     * @throws IllegalArgumentException если родитель не найден / не принадлежит юзеру / в архиве
     */
    @Transactional
    public Plant createCutting(User user, Long parentId, String cuttingName) {
        String name = cuttingName == null ? "" : cuttingName.trim();
        if (name.isBlank()) {
            throw new IllegalArgumentException("Имя растения не может быть пустым");
        }

        Plant parent = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(user.getId(), parentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Материнское растение не найдено"));

        // Активные расписания родителя — копируем только их интервалы (отключённые
        // тумблером не переносим, как и при сохранении шаблона из растения).
        List<CareSchedule> parentSchedules = careScheduleRepository
                .findAllByPlantId(parent.getId()).stream()
                .filter(CareSchedule::isActive)
                .toList();

        Plant cutting = Plant.builder()
                .user(user)
                .species(parent.getSpecies())
                .location(parent.getLocation())
                .parent(parent)
                .name(name)
                .build();
        cutting = plantRepository.save(cutting);

        // Копируем интервалы как новые расписания. nextDueAt считаем от now,
        // потому что у черенка собственный график ухода.
        LocalDateTime now = LocalDateTime.now();
        for (CareSchedule parentSchedule : parentSchedules) {
            int interval = parentSchedule.getIntervalDays();
            int effective = seasonalIntervalService.effectiveIntervalDays(cutting, user, interval);
            CareSchedule copy = CareSchedule.builder()
                    .plant(cutting)
                    .taskType(parentSchedule.getTaskType())
                    .intervalDays(interval)
                    .nextDueAt(now.plusDays(effective))
                    .active(true)
                    .build();
            careScheduleRepository.save(copy);
        }

        log.info("Created cutting '{}' (id={}) from parent {} for user {} ({} schedules copied)",
                name, cutting.getId(), parent.getId(), user.getId(), parentSchedules.size());

        return cutting;
    }

    /**
     * Финальный шаг создания растения. Теперь блокирующий: вместо немедленного
     * показа карточки сначала задаём вопрос про акклиматизацию (issue #75) —
     * пользователь должен ответить, и только после этого:
     *   1) включаем (или нет) acclimation
     *   2) показываем карточку
     *   3) сбрасываем state, отправляем главное меню
     *
     * Завершение этого flow живёт в {@link #completePlantCreationAfterAcclimation}.
     */
    @Transactional
    public void finishPlantCreation(User user, Plant plant, TelegramClient client) {
        userService.updateState(user, ConversationState.AWAITING_PLANT_ACCLIMATION_CHOICE);
        userService.setStateData(user, "acclimation_plant_id", String.valueOf(plant.getId()));

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("✅ Да, новое")
                                .callbackData("PLANT:ACCL:YES:" + plant.getId())
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("❌ Нет")
                                .callbackData("PLANT:ACCL:NO:" + plant.getId())
                                .build()
                )))
                .build();

        String text = "✅ Растение *" + escapeMd(plant.getName()) + "* добавлено!\n\n"
                + "🆕 Это новое растение (недавно куплено или переехало)?\n\n"
                + "_Если да — включу режим акклиматизации на "
                + PlantAcclimationService.ACCLIMATION_DAYS
                + " дней: буду напоминать мягче и просить проверять грунт._";

        sendTextWithKeyboard(user.getTelegramChatId(), text, keyboard, client);

        log.info(
                "Plant creation -> awaiting acclimation choice for user {}, plant={}",
                user.getTelegramChatId(),
                plant.getId()
        );
    }

    /**
     * Завершение создания после ответа на вопрос про акклиматизацию (issue #75).
     * Включает или не включает режим, показывает карточку, главное меню,
     * сбрасывает state.
     */
    @Transactional
    public void completePlantCreationAfterAcclimation(
            User user, Plant plant, boolean acclimationEnabled, TelegramClient client
    ) {
        // Перезагружаем растение в ТЕКУЩЕЙ транзакции: переданный plant приходит
        // из PlantAcclimationService.enable() / plantService.getPlantForUser(), чьи
        // транзакции уже закрыты — обращение к lazy plant.getLocation() в этой
        // сессии падает в LazyInitializationException. Свежая загрузка привязывает
        // entity к нашей текущей сессии и позволяет дёрнуть location без проблем.
        Plant fresh = plantRepository.findById(plant.getId()).orElse(null);
        if (fresh == null) {
            log.error(
                    "Plant {} disappeared during wizard completion for user {}",
                    plant.getId(),
                    user.getTelegramChatId()
            );
            return;
        }

        if (acclimationEnabled) {
            String confirm = "🆕 Ок, включаю акклиматизацию на "
                    + PlantAcclimationService.ACCLIMATION_DAYS + " дней.\n"
                    + "Буду напоминать мягче и просить проверять грунт/листья.";
            sendTextMessage(user.getTelegramChatId(), confirm, client);
        }

        sendCreatedPlantCard(user, fresh, client);

        userService.resetToIdle(user);
        mainMenuService.sendMainMenu(user, client);

        log.info(
                "Plant creation completed for user {}, plant={}, acclimation={}",
                user.getTelegramChatId(),
                fresh.getId(),
                acclimationEnabled
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

        // issue #117: «С тобой с …» — только если юзер указал дату.
        appendAcquiredAtLine(text, plant);

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
                        .append(" дн.");
                // Если для этого растения сезонность активна — дописываем
                // фактический интервал текущего сезона (issue #67).
                if (seasonalIntervalService.isSeasonalActive(plant, plant.getUser())) {
                    int eff = seasonalIntervalService.effectiveIntervalDays(
                            plant, plant.getUser(), schedule.getIntervalDays());
                    if (eff != schedule.getIntervalDays()) {
                        text.append(" (сейчас ")
                                .append(seasonalIntervalService.currentSeason(plant.getUser())
                                        .displayName())
                                .append(" → ").append(eff).append(" дн.)");
                    }
                }
                text.append("\n");
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

        // issue #130: инициализируем lazy-вид, чтобы прочитать флаги токсичности
        // в buildDetailedCardText (иначе LazyInitializationException вне транзакции).
        if (plant.getSpecies() != null) {
            plant.getSpecies().getName();
        }

        // issue #129: показываем кнопку «О виде» только если у вида есть факты.
        // Пустой/неизвестный вид кнопку не получает.
        boolean hasSpeciesFacts = plant.getSpecies() != null
                && speciesFactService.hasFactsForSpecies(plant.getSpecies().getId());

        // issue #139: родословная. Счётчик потомков для строки «🌱 Потомки: N» и
        // ссылка на родителя (кликабельная кнопка) для потомка. parent — lazy,
        // инициализируем имя/архивность здесь, внутри read-only транзакции.
        long childrenCount = plantRepository.countByParentId(plant.getId());
        Plant parent = plant.getParent();
        if (parent != null) {
            parent.getName();
            parent.getArchivedAt();
        }

        String text = buildDetailedCardText(plant, schedules, childrenCount);
        InlineKeyboardMarkup keyboard =
                buildDetailedCardKeyboard(plant, schedules, backTarget, hasSpeciesFacts, parent);

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
                        .text("🍂 Сезонность: " + formatSeasonalOverride(plant))
                        .callbackData("PLANT:SEASONAL:" + plant.getId() + backSuffix(backTarget))
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
                .append(inUserZone(schedule.getNextDueAt(), plant.getUser()).format(DATE_FMT))
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
    // 2d) Экран «🌍 О виде» — энциклопедия фактов (issue #129)
    // =================================================================

    /**
     * Экран энциклопедических фактов вида (issue #129). Показывает факты,
     * сгруппированные по категориям, с человекочитаемыми заголовками.
     * Если у вида нет фактов (или вид не задан) — сообщаем об этом и
     * предлагаем вернуться к карточке; кнопка «О виде» в норме сюда не ведёт,
     * если фактов нет, но проверку дублируем на случай гонки/удаления контента.
     */
    @Transactional(readOnly = true)
    public void showSpeciesFactsScreen(
            User user,
            Long plantId,
            Integer messageId,
            String backTarget,
            TelegramClient client
    ) {
        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .orElse(null);
        if (plant == null) {
            sendTextMessage(user.getTelegramChatId(), "❌ Растение не найдено.", client);
            return;
        }

        java.util.Map<com.plantcare.bot.domain.enums.FactCategory, List<SpeciesFactDto>> factsByCategory =
                plant.getSpecies() == null
                        ? java.util.Map.of()
                        : speciesFactService.getFactsBySpecies(plant.getSpecies().getId(), null);

        String text = buildSpeciesFactsText(plant, factsByCategory);
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("⬅️ К карточке")
                                .callbackData(plantCardCallback(plant.getId(), backTarget))
                                .build()
                )))
                .build();

        sendOrEditText(user.getTelegramChatId(), messageId, text, keyboard, client);
    }

    private String buildSpeciesFactsText(
            Plant plant,
            java.util.Map<com.plantcare.bot.domain.enums.FactCategory, List<SpeciesFactDto>> factsByCategory
    ) {
        StringBuilder sb = new StringBuilder();

        String speciesName = plant.getSpecies() != null ? plant.getSpecies().getName() : plant.getName();
        sb.append("🌍 *О виде — ").append(escapeMd(speciesName)).append("*\n");

        if (factsByCategory.isEmpty()) {
            sb.append("\nПока нет фактов об этом виде.");
            return sb.toString();
        }

        // Фиксированный порядок категорий для предсказуемого вида карточки.
        for (com.plantcare.bot.domain.enums.FactCategory category :
                com.plantcare.bot.domain.enums.FactCategory.values()) {
            List<SpeciesFactDto> facts = factsByCategory.get(category);
            if (facts == null || facts.isEmpty()) {
                continue;
            }

            sb.append("\n").append(factCategoryEmoji(category)).append(" *")
                    .append(factCategoryTitle(category)).append("*\n");

            for (SpeciesFactDto fact : facts) {
                sb.append("• ");
                if (fact.title() != null && !fact.title().isBlank()) {
                    sb.append("*").append(escapeMd(fact.title())).append("* — ");
                }
                sb.append(escapeMd(fact.body()));
                if (fact.source() != null && !fact.source().isBlank()) {
                    sb.append("\n  _Источник: ").append(escapeMd(fact.source())).append("_");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private String factCategoryTitle(com.plantcare.bot.domain.enums.FactCategory category) {
        return switch (category) {
            case ORIGIN    -> "Происхождение";
            case CARE      -> "Уход";
            case TOXICITY  -> "Токсичность";
            case CURIOSITY -> "Интересное";
        };
    }

    private String factCategoryEmoji(com.plantcare.bot.domain.enums.FactCategory category) {
        return switch (category) {
            case ORIGIN    -> "🗺";
            case CARE      -> "🪴";
            case TOXICITY  -> "⚠️";
            case CURIOSITY -> "✨";
        };
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
        return TimezoneSupport.zoneOf(user);
    }

    /**
     * Конвертация «голого» {@link LocalDateTime} (он хранится в БД в UTC)
     * в {@link LocalDate} в TZ юзера. Делегирует в {@link TimezoneSupport}.
     */
    private LocalDate inUserZone(LocalDateTime utc, User user) {
        return TimezoneSupport.dateInUserZone(utc, user);
    }

    /** «Сегодня» в TZ юзера — для корректных сравнений с {@link #inUserZone}. */
    private LocalDate todayInUserZone(User user) {
        return TimezoneSupport.todayInUserZone(user);
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

        // Даты событий — в TZ юзера, чтобы «01.05» не превратилось в «30.04»
        // у юзеров в +3..+5 при создании события поздно вечером по UTC.
        User user = plant.getUser();
        for (PlantEvent e : events) {
            String date = inUserZone(e.getEventDate(), user).format(HISTORY_DATE_FMT);
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

    private String buildDetailedCardText(Plant plant, List<CareSchedule> schedules, long childrenCount) {
        StringBuilder sb = new StringBuilder();

        sb.append("🌿 *").append(escapeMd(plant.getName())).append("*\n");

        if (plant.getLocation() != null) {
            sb.append("📍 ").append(escapeMd(plant.getLocation().getDisplayName())).append("\n");
        }

        // issue #139: счётчик потомков. Показываем строку только если есть хотя бы
        // один черенок — для растений без потомков строку не печатаем (как другие
        // условные строки карточки).
        if (childrenCount > 0) {
            sb.append("🌱 Потомки: ").append(childrenCount).append("\n");
        }

        // issue #138: health-score после локации/потомков, до «С тобой с …».
        appendHealthScoreLine(sb, plant);

        // issue #117: «С тобой с …» — после имени/локации, до фото/баннеров.
        appendAcquiredAtLine(sb, plant);

        if (plant.getPhotoFileId() != null && !plant.getPhotoFileId().isBlank()) {
            sb.append("📷 Фото загружено\n");
        }

        // issue #75: баннер режима акклиматизации
        if (plant.isInAcclimation(LocalDateTime.now())) {
            sb.append("🆕 *Акклиматизация:* до ")
                    .append(inUserZone(plant.getAcclimationUntil(), plant.getUser()).format(DATE_FMT))
                    .append("\n");
        }

        if (plant.getNotes() != null && !plant.getNotes().isBlank()) {
            sb.append("\n📝 _").append(escapeMd(plant.getNotes().trim())).append("_\n");
        }

        // issue #130: блок токсичности вида. Показываем только флаги == true.
        appendToxicityBlock(sb, plant.getSpecies());

        if (schedules.isEmpty()) {
            sb.append("\n📅 Расписание ухода не настроено.");
            return sb.toString();
        }

        sb.append("\n📅 *Ближайший уход:*\n");

        // Сортируем для стабильного порядка: WATERING, MISTING, FERTILIZING.
        List<CareSchedule> sorted = schedules.stream()
                .sorted(Comparator.comparing(s -> s.getTaskType().ordinal()))
                .toList();

        // «Сегодня» считаем в TZ юзера — иначе у юзеров в +3..+5 ночью
        // показывалось «вчера» вместо «сегодня» (today брался по UTC).
        LocalDate today = todayInUserZone(plant.getUser());

        for (CareSchedule schedule : sorted) {
            // next_due_at хранится UTC; для отображения переводим в TZ юзера.
            LocalDate due = inUserZone(schedule.getNextDueAt(), plant.getUser());
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

    /**
     * Дописывает блок токсичности вида (issue #130). Бейдж выводится только для
     * флагов со значением {@code true}; {@code false} и {@code null} (нет данных)
     * не показываются. Если ни один флаг не {@code true} — блок отсутствует целиком.
     */
    private void appendToxicityBlock(StringBuilder sb, com.plantcare.bot.domain.Species species) {
        if (species == null) {
            return;
        }

        boolean toCats = Boolean.TRUE.equals(species.getToxicToCats());
        boolean toDogs = Boolean.TRUE.equals(species.getToxicToDogs());
        boolean toHumans = Boolean.TRUE.equals(species.getToxicToHumans());

        if (!toCats && !toDogs && !toHumans) {
            return;
        }

        sb.append("\n");
        if (toCats) {
            sb.append("⚠️ токсично для кошек\n");
        }
        if (toDogs) {
            sb.append("⚠️ токсично для собак\n");
        }
        if (toHumans) {
            sb.append("⚠️ токсично для детей\n");
        }
    }

    private InlineKeyboardMarkup buildDetailedCardKeyboard(
            Plant plant,
            List<CareSchedule> schedules,
            String backTarget,
            boolean hasSpeciesFacts,
            Plant parent
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

            // 1a) Ретро-отметка (issue #118): «Я уже ухаживал». Показываем только
            // когда активные расписания есть — иначе ретро отмечать просто нечего.
            // Формулировку выбираем компактно по числу активных типов: для одного
            // — точное слово ("полил/опрыскал/удобрил"), для нескольких — обобщённо.
            InlineKeyboardRow retroRow = new InlineKeyboardRow();
            retroRow.add(InlineKeyboardButton.builder()
                    .text(retroCareLabel(sorted))
                    .callbackData("v1:back_care:" + plant.getId())
                    .build());
            rows.add(retroRow);
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

        // 2a) Диагностика растения (issue #73) + взять черенок (issue #139).
        InlineKeyboardRow diagnosisRow = new InlineKeyboardRow();
        diagnosisRow.add(InlineKeyboardButton.builder()
                .text("🩺 Диагностика")
                .callbackData("PLANT:DIAG:START:" + plant.getId())
                .build());
        diagnosisRow.add(InlineKeyboardButton.builder()
                .text("🌱 Взять черенок")
                .callbackData("PLANT:CUTTING:" + plant.getId())
                .build());
        rows.add(diagnosisRow);

        // 2a') «О виде» (issue #129): энциклопедические факты вида. Кнопку
        // показываем только если у вида реально есть факты (hasSpeciesFacts).
        if (hasSpeciesFacts) {
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text("🌍 О виде")
                            .callbackData("PLANT:SPECIES_FACTS:" + plant.getId() + backSuffix(backTarget))
                            .build()
            )));
        }

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

        // 2b') Фото-прогресс (issue #72). Отдельный ряд между Events и Acclimation —
        // экран фото-прогресса не сохраняет back-контекст «комнаты», его всегда
        // ведём на PLANT:VIEW.
        InlineKeyboardRow photoProgressRow = new InlineKeyboardRow();
        photoProgressRow.add(InlineKeyboardButton.builder()
                .text("📸 Фото-прогресс")
                .callbackData("PHOTO_PROGRESS:VIEW:" + plant.getId())
                .build());
        rows.add(photoProgressRow);

        // 2c) Кнопка выключения акклиматизации (issue #75) — показываем только если режим активен.
        if (plant.isInAcclimation(LocalDateTime.now())) {
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text("🛑 Выключить акклиматизацию")
                            .callbackData("PLANT:ACCL:DISABLE:" + plant.getId() + backSuffix(backTarget))
                            .build()
            )));
        }

        // 2d) Ссылка на материнское растение (issue #139), если это черенок.
        // Кликабельная: ведёт на карточку родителя. Архивный родитель помечается
        // «(в архиве)» и ведёт на ARCHIVE:VIEW — ссылка сохраняется (ADR-009).
        if (parent != null) {
            boolean parentArchived = parent.getArchivedAt() != null;
            String label = "⬅️ Родитель: " + parent.getName()
                    + (parentArchived ? " (в архиве)" : "");
            String callback = parentArchived
                    ? "ARCHIVE:VIEW:" + parent.getId()
                    : "PLANT:VIEW:" + parent.getId();
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text(label)
                            .callbackData(callback)
                            .build()
            )));
        }

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
     * Текст кнопки ретро-отметки ухода в карточке (issue #118).
     * Если активный тип один — берём его глагол: «Я уже полил», «Я уже опрыскал»,
     * «Я уже удобрил». Если их несколько — общее «Я уже ухаживал», чтобы кнопка
     * не вырастала в полстроки. SOIL_CHECK как таковой ретро-отметкой не считаем
     * (это not-care, а check), его сюда не пускаем.
     */
    private String retroCareLabel(List<CareSchedule> activeSchedules) {
        long careTypes = activeSchedules.stream()
                .map(CareSchedule::getTaskType)
                .filter(t -> t != TaskType.SOIL_CHECK)
                .distinct()
                .count();
        if (careTypes == 1) {
            TaskType only = activeSchedules.stream()
                    .map(CareSchedule::getTaskType)
                    .filter(t -> t != TaskType.SOIL_CHECK)
                    .findFirst()
                    .orElse(TaskType.WATERING);
            return "✅ Я уже " + doneVerb(only).toLowerCase();
        }
        return "✅ Я уже ухаживал";
    }

    /**
     * Добавляет в карточку строку health-score (issue #138):
     * «Health: 82 🟢» либо «Health: Пока мало данных», если действий &lt; 3.
     */
    private void appendHealthScoreLine(StringBuilder sb, Plant plant) {
        HealthScoreService.HealthScore health = healthScoreService.computeForPlant(plant);
        if (health.insufficientData()) {
            sb.append("❤️ Health: Пока мало данных\n");
        } else {
            sb.append("❤️ Health: ")
                    .append(health.score())
                    .append(" ")
                    .append(health.zone().emoji())
                    .append("\n");
        }
    }

    /**
     * Добавляет в текст карточки строку «🌱 С тобой с DD MMMM YYYY (возраст)»,
     * если у растения проставлен {@code acquiredAt} (issue #117).
     * Возраст печатается до архивации — для активного растения «сегодня»,
     * для архивного передавай {@code referenceDay = archivedAt.toLocalDate()}.
     */
    private void appendAcquiredAtLine(StringBuilder sb, Plant plant) {
        LocalDate acquiredAt = plant.getAcquiredAt();
        if (acquiredAt == null) {
            return;
        }
        LocalDate reference = todayInUserZone(plant.getUser());
        appendAcquiredAtLine(sb, acquiredAt, reference);
    }

    /**
     * Вариант с явной reference-датой — для архивных карточек, где возраст
     * считается до даты архивации, а не до сегодня.
     */
    void appendAcquiredAtLine(StringBuilder sb, LocalDate acquiredAt, LocalDate reference) {
        if (acquiredAt == null) {
            return;
        }
        sb.append("🌱 С тобой с ")
                .append(acquiredAt.format(ACQUIRED_DATE_FMT))
                .append(" (")
                .append(formatPlantAge(acquiredAt, reference))
                .append(")\n");
    }

    /**
     * Возраст растения «N лет M месяцев» с правильным русским склонением
     * (issue #117). Если ≤ acquiredAt + 1 месяц — «меньше месяца».
     * Если месяцы = 0 — печатаем только годы; если годы = 0 — только месяцы.
     */
    static String formatPlantAge(LocalDate acquiredAt, LocalDate reference) {
        if (acquiredAt == null || reference == null || !reference.isAfter(acquiredAt)) {
            return "меньше месяца";
        }
        Period p = Period.between(acquiredAt, reference);
        int years = p.getYears();
        int months = p.getMonths();

        if (years == 0 && months == 0) {
            return "меньше месяца";
        }

        StringBuilder out = new StringBuilder();
        if (years > 0) {
            out.append(years).append(' ').append(pluralizeYears(years));
        }
        if (months > 0) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(months).append(' ').append(pluralizeMonths(months));
        }
        return out.toString();
    }

    private static String pluralizeYears(int n) {
        int mod10 = Math.abs(n) % 10;
        int mod100 = Math.abs(n) % 100;
        if (mod10 == 1 && mod100 != 11) return "год";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "года";
        return "лет";
    }

    private static String pluralizeMonths(int n) {
        int mod10 = Math.abs(n) % 10;
        int mod100 = Math.abs(n) % 100;
        if (mod10 == 1 && mod100 != 11) return "месяц";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "месяца";
        return "месяцев";
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

    /**
     * Подпись для кнопки «🍂 Сезонность» в edit-menu растения (issue #67).
     */
    private static String formatSeasonalOverride(Plant plant) {
        com.plantcare.bot.domain.enums.SeasonalOverride o = plant.getSeasonalOverride();
        if (o == null) o = com.plantcare.bot.domain.enums.SeasonalOverride.INHERIT;
        return switch (o) {
            case INHERIT -> "Наследовать";
            case ON      -> "Включена";
            case OFF     -> "Выключена";
        };
    }

    /**
     * Циклическое переключение per-plant seasonal override (issue #67):
     * INHERIT → ON → OFF → INHERIT. Вызывается из callback'а
     * {@code PLANT:SEASONAL:<plantId>...} и перерисовывает edit-меню.
     */
    @Transactional
    public void cycleSeasonalOverride(
            User user, Long plantId, Integer messageId,
            String backTarget, TelegramClient client
    ) {
        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .orElse(null);
        if (plant == null) {
            log.warn("cycleSeasonalOverride: plant {} not found for user {}",
                    plantId, user.getId());
            return;
        }
        com.plantcare.bot.domain.enums.SeasonalOverride cur = plant.getSeasonalOverride();
        if (cur == null) cur = com.plantcare.bot.domain.enums.SeasonalOverride.INHERIT;
        com.plantcare.bot.domain.enums.SeasonalOverride next = switch (cur) {
            case INHERIT -> com.plantcare.bot.domain.enums.SeasonalOverride.ON;
            case ON      -> com.plantcare.bot.domain.enums.SeasonalOverride.OFF;
            case OFF     -> com.plantcare.bot.domain.enums.SeasonalOverride.INHERIT;
        };
        plant.setSeasonalOverride(next);
        plantRepository.save(plant);
        log.info("Plant {} seasonal override: {} → {} (user {})",
                plantId, cur, next, user.getId());

        showSettingsScreen(user, plantId, messageId, backTarget, client);
    }
}

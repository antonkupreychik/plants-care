package com.plantcare.bot.service;

import com.plantcare.core.service.CareHistoryService;
import com.plantcare.core.service.HealthScoreService;
import com.plantcare.core.service.PlantEventService;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.SpeciesFactService;
import com.plantcare.core.service.UserService;

import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.seasonal.service.SeasonalIntervalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты рендера детальной карточки растения {@link PlantCardService#showPlantCard}
 * и связанного клавиатурного меню (issue #26, #67, #75, #129, #139).
 *
 * <p>Мокаем всех коллабораторов и проверяем реально отправленный текст/клавиатуру
 * через захват {@link SendMessage}, как это уже делает {@code PlantCardToxicityTest}.
 */
@ExtendWith(MockitoExtension.class)
class PlantCardServiceScreensTest {

    @Mock private PlantService plantService;
    @Mock private PlantRepository plantRepository;
    @Mock private CareScheduleRepository careScheduleRepository;
    @Mock private MainMenuService mainMenuService;
    @Mock private UserService userService;
    @Mock private CareHistoryService careHistoryService;
    @Mock private HealthScoreService healthScoreService;
    @Mock private PlantEventService plantEventService;
    @Mock private SpeciesFactService speciesFactService;
    @Mock private SeasonalIntervalService seasonalIntervalService;

    @Mock private TelegramClient client;

    private PlantCardService cardService;

    @BeforeEach
    void setUp() {
        cardService = new PlantCardService(
                plantService, plantRepository, careScheduleRepository, mainMenuService, userService,
                careHistoryService, healthScoreService, plantEventService, speciesFactService,
                seasonalIntervalService);

        lenient().when(healthScoreService.computeForPlant(any()))
                .thenReturn(HealthScoreService.HealthScore.insufficient());
    }

    // ==================================================================
    // showPlantCard
    // ==================================================================

    @Test
    void should_send_not_found_message_when_plant_missing_on_show_card() {
        User user = givenUser(1L, 555L);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.empty());

        cardService.showPlantCard(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        assertThat(sentText()).contains("❌ Растение не найдено");
    }

    @Test
    void should_render_location_line_and_photo_button_when_plant_has_location_and_photo() {
        User user = givenUser(1L, 555L);
        Location loc = Location.builder().name("Кухня").emoji("🍳").build();
        Plant plant = givenPlant(10L, user, null);
        plant.setLocation(loc);
        plant.setPhotoFileId("file-1");
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of());

        cardService.showPlantCard(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        String text = sentText();
        assertThat(text).contains("🍳 Кухня");
        assertThat(text).contains("📷 Фото загружено");
        assertThat(buttonCallbacks()).contains("PLANT:PHOTO:10");
    }

    @Test
    void should_show_no_schedule_line_when_plant_has_no_active_schedules() {
        User user = givenUser(1L, 555L);
        Plant plant = givenPlant(10L, user, null);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of());

        cardService.showPlantCard(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        assertThat(sentText()).contains("📅 Расписание ухода не настроено.");
        // Без расписаний ряд быстрой отметки не рисуется.
        assertThat(buttonCallbacks()).noneMatch(cb -> cb.startsWith("PLANT:CARE:"));
    }

    @Test
    void should_render_overdue_today_and_future_due_labels_for_active_schedules() {
        User user = givenUser(1L, 555L);
        Plant plant = givenPlant(10L, user, null);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));

        var today = java.time.LocalDate.now(ZoneOffset.UTC);
        CareSchedule watering = CareSchedule.builder()
                .taskType(TaskType.WATERING)
                .intervalDays(7)
                .nextDueAt(today.minusDays(2).atStartOfDay())
                .active(true)
                .build();
        CareSchedule misting = CareSchedule.builder()
                .taskType(TaskType.MISTING)
                .intervalDays(3)
                .nextDueAt(today.atStartOfDay())
                .active(true)
                .build();
        CareSchedule fertilizing = CareSchedule.builder()
                .taskType(TaskType.FERTILIZING)
                .intervalDays(30)
                .nextDueAt(today.plusDays(1).atStartOfDay())
                .active(true)
                .build();
        when(plantService.getActiveSchedules(10L))
                .thenReturn(List.of(watering, misting, fertilizing));

        cardService.showPlantCard(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        String text = sentText();
        assertThat(text).contains("💧 Полив — ⚠️ просрочено на 2 дн.");
        assertThat(text).contains("💨 Опрыскивание — сегодня");
        assertThat(text).contains("🌿 Удобрение — завтра");
        assertThat(buttonCallbacks()).contains(
                "PLANT:CARE:10:WATERING", "PLANT:CARE:10:MISTING", "PLANT:CARE:10:FERTILIZING");
    }

    @Test
    void should_render_far_future_due_date_as_formatted_date() {
        User user = givenUser(1L, 555L);
        Plant plant = givenPlant(10L, user, null);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));

        var farFuture = java.time.LocalDate.now(ZoneOffset.UTC).plusDays(10);
        CareSchedule watering = CareSchedule.builder()
                .taskType(TaskType.WATERING)
                .intervalDays(7)
                .nextDueAt(farFuture.atStartOfDay())
                .active(true)
                .build();
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of(watering));

        cardService.showPlantCard(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        assertThat(sentText()).contains(
                "💧 Полив — " + farFuture.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM")));
    }

    @Test
    void should_render_acclimation_banner_and_disable_button_when_plant_in_acclimation() {
        User user = givenUser(1L, 555L);
        Plant plant = givenPlant(10L, user, null);
        plant.setAcclimationUntil(LocalDateTime.now().plusDays(5));
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of());

        cardService.showPlantCard(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        assertThat(sentText()).contains("🆕 *Акклиматизация:* до");
        assertThat(buttonCallbacks()).contains("PLANT:ACCL:DISABLE:10");
    }

    @Test
    void should_not_render_acclimation_block_when_plant_not_in_acclimation() {
        User user = givenUser(1L, 555L);
        Plant plant = givenPlant(10L, user, null);
        plant.setAcclimationUntil(LocalDateTime.now().minusDays(1));
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of());

        cardService.showPlantCard(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        String text = sentText();
        assertThat(text).doesNotContain("Акклиматизация");
        assertThat(buttonCallbacks()).noneMatch(cb -> cb.startsWith("PLANT:ACCL:DISABLE:"));
    }

    @Test
    void should_render_notes_line_when_plant_has_notes() {
        User user = givenUser(1L, 555L);
        Plant plant = givenPlant(10L, user, null);
        plant.setNotes("  Любит утреннее солнце  ");
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of());

        cardService.showPlantCard(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        assertThat(sentText()).contains("📝 _Любит утреннее солнце_");
    }

    @Test
    void should_show_species_facts_button_and_parent_link_when_present() {
        User user = givenUser(1L, 555L);
        Species species = givenSpecies(100L);
        Plant parent = givenPlant(5L, user, null);
        parent.setName("Мама-монстера");
        Plant plant = givenPlant(10L, user, species);
        plant.setParent(parent);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of());
        when(speciesFactService.hasFactsForSpecies(100L)).thenReturn(true);

        cardService.showPlantCard(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        assertThat(buttonCallbacks()).contains("PLANT:SPECIES_FACTS:10");
        assertThat(buttonTexts()).contains("⬅️ Родитель: Мама-монстера");
        assertThat(buttonCallbacks()).contains("PLANT:VIEW:5");
    }

    @Test
    void should_show_archived_parent_link_leading_to_archive_view() {
        User user = givenUser(1L, 555L);
        Plant parent = givenPlant(5L, user, null);
        parent.setName("Старая монстера");
        parent.setArchivedAt(LocalDateTime.now().minusDays(30));
        Plant plant = givenPlant(10L, user, null);
        plant.setParent(parent);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of());

        cardService.showPlantCard(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        assertThat(buttonTexts()).contains("⬅️ Родитель: Старая монстера (в архиве)");
        assertThat(buttonCallbacks()).contains("ARCHIVE:VIEW:5");
    }

    @Test
    void should_use_location_back_button_when_back_target_is_location() {
        User user = givenUser(1L, 555L);
        Plant plant = givenPlant(10L, user, null);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of());

        cardService.showPlantCard(user, 10L, null,
                PlantCardService.BACK_TO_LOCATION_PREFIX + "7", client);

        assertThat(buttonCallbacks()).contains("LOCATION:VIEW:7");
        assertThat(buttonCallbacks()).contains(
                "PLANT:SETTINGS:10:" + PlantCardService.BACK_TO_LOCATION_PREFIX + "7");
    }

    // ==================================================================
    // showSettingsScreen
    // ==================================================================

    @Test
    void should_send_not_found_message_when_plant_missing_on_settings_screen() {
        User user = givenUser(1L, 555L);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.empty());

        cardService.showSettingsScreen(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        assertThat(sentText()).contains("❌ Растение не найдено.");
    }

    @Test
    void should_show_nearest_reminder_button_when_schedules_present_on_settings_screen() {
        User user = givenUser(1L, 555L);
        Plant plant = givenPlant(10L, user, null);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of(
                CareSchedule.builder().taskType(TaskType.WATERING).intervalDays(7)
                        .nextDueAt(LocalDateTime.now()).active(true).build()
        ));

        cardService.showSettingsScreen(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        assertThat(buttonCallbacks()).contains("PLANT:SCHED:NEAREST:10");
    }

    @Test
    void should_hide_nearest_reminder_button_when_no_schedules_on_settings_screen() {
        User user = givenUser(1L, 555L);
        Plant plant = givenPlant(10L, user, null);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of());

        cardService.showSettingsScreen(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        assertThat(buttonCallbacks()).noneMatch(cb -> cb.startsWith("PLANT:SCHED:NEAREST:"));
    }

    @Test
    void should_render_seasonal_override_label_on_settings_screen() {
        User user = givenUser(1L, 555L);
        Plant plant = givenPlant(10L, user, null);
        plant.setSeasonalOverride(com.plantcare.core.domain.enums.SeasonalOverride.ON);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of());

        cardService.showSettingsScreen(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        assertThat(buttonTexts()).contains("🍂 Сезонность: Включена");
    }

    // ==================================================================
    // showDeleteConfirmScreen
    // ==================================================================

    @Test
    void should_send_not_found_message_when_plant_missing_on_delete_confirm() {
        User user = givenUser(1L, 555L);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.empty());

        cardService.showDeleteConfirmScreen(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        assertThat(sentText()).contains("❌ Растение не найдено.");
    }

    @Test
    void should_render_confirm_and_cancel_buttons_on_delete_confirm_screen() {
        User user = givenUser(1L, 555L);
        Plant plant = givenPlant(10L, user, null);
        plant.setName("Фикус");
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));

        cardService.showDeleteConfirmScreen(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        String text = sentText();
        assertThat(text).contains("🗑 *Удалить растение?*").contains("Фикус");
        assertThat(buttonCallbacks()).contains("PLANT:EDIT:DELETE_CONFIRM:10", "PLANT:SETTINGS:10");
    }

    // ------------------------------------------------------------------ helpers

    private String sentText() {
        try {
            var captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(client).execute(captor.capture());
            return captor.getValue().getText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<String> buttonCallbacks() {
        try {
            var captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(client).execute(captor.capture());
            InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
            return keyboard.getKeyboard().stream()
                    .flatMap(Collection::stream)
                    .map(InlineKeyboardButton::getCallbackData)
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<String> buttonTexts() {
        try {
            var captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(client).execute(captor.capture());
            InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
            return keyboard.getKeyboard().stream()
                    .flatMap(Collection::stream)
                    .map(InlineKeyboardButton::getText)
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private User givenUser(Long id, Long chatId) {
        var u = User.builder().telegramChatId(chatId).timezone("UTC").build();
        setId(u, id);
        return u;
    }

    private Species givenSpecies(Long id) {
        var s = new Species();
        s.setName("Монстера");
        setId(s, id);
        return s;
    }

    private Plant givenPlant(Long id, User user, Species species) {
        var p = new Plant();
        p.setName("Моя монстера");
        p.setUser(user);
        p.setSpecies(species);
        setId(p, id);
        return p;
    }

    private static void setId(Object entity, Long id) {
        try {
            Field f = com.plantcare.core.domain.base.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

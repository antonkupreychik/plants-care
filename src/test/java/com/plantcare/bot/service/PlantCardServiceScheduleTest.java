package com.plantcare.bot.service;

import com.plantcare.core.service.CareHistoryService;
import com.plantcare.core.service.HealthScoreService;
import com.plantcare.core.service.PlantEventService;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.SpeciesFactService;
import com.plantcare.core.service.UserService;

import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.SeasonalOverride;
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
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты экранов управления расписанием ухода и сезонностью
 * (issue #26, #67): «Ближайшее напоминание», редактирование конкретного
 * типа, «Типы ухода», циклическое переключение per-plant seasonal override.
 */
@ExtendWith(MockitoExtension.class)
class PlantCardServiceScheduleTest {

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
    // showNearestScheduleScreen
    // ==================================================================

    @Test
    void should_send_not_found_message_when_plant_missing_on_nearest_schedule() {
        User user = givenUser(1L, 555L);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.empty());

        cardService.showNearestScheduleScreen(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        assertThat(sentText()).contains("❌ Растение не найдено.");
    }

    @Test
    void should_fallback_to_care_types_screen_when_no_active_schedule_for_nearest() {
        User user = givenUser(1L, 555L);
        Plant plant = givenPlant(10L, user);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of());
        when(plantService.getAllSchedules(10L)).thenReturn(List.of());

        cardService.showNearestScheduleScreen(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        assertThat(sentText()).contains("🔔 *Типы ухода*");
    }

    @Test
    void should_show_earliest_due_schedule_on_nearest_schedule_screen() {
        User user = givenUser(1L, 555L);
        Plant plant = givenPlant(10L, user);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));

        CareSchedule later = CareSchedule.builder()
                .taskType(TaskType.FERTILIZING).intervalDays(30)
                .nextDueAt(LocalDateTime.of(2026, 6, 1, 0, 0)).active(true).build();
        CareSchedule earlier = CareSchedule.builder()
                .taskType(TaskType.WATERING).intervalDays(7)
                .nextDueAt(LocalDateTime.of(2026, 5, 1, 0, 0)).active(true).build();
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of(later, earlier));

        cardService.showNearestScheduleScreen(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        String text = sentText();
        assertThat(text).contains("💧 *Полив*");
        assertThat(text).contains("📅 Каждые 7 дн.");
    }

    // ==================================================================
    // showScheduleEditByType
    // ==================================================================

    @Test
    void should_send_not_found_message_when_plant_missing_on_schedule_edit_by_type() {
        User user = givenUser(1L, 555L);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.empty());

        cardService.showScheduleEditByType(
                user, 10L, TaskType.WATERING, null, PlantCardService.BACK_TO_LIST, client);

        assertThat(sentText()).contains("❌ Растение не найдено.");
    }

    @Test
    void should_fallback_to_care_types_screen_when_requested_type_not_active() {
        User user = givenUser(1L, 555L);
        Plant plant = givenPlant(10L, user);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of());
        when(plantService.getAllSchedules(10L)).thenReturn(List.of());

        cardService.showScheduleEditByType(
                user, 10L, TaskType.MISTING, null, PlantCardService.BACK_TO_LIST, client);

        assertThat(sentText()).contains("🔔 *Типы ухода*");
    }

    @Test
    void should_show_schedule_edit_screen_with_postpone_buttons_for_requested_type() {
        User user = givenUser(1L, 555L);
        Plant plant = givenPlant(10L, user);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));
        CareSchedule schedule = CareSchedule.builder()
                .taskType(TaskType.MISTING).intervalDays(3)
                .nextDueAt(LocalDateTime.of(2026, 5, 10, 0, 0)).active(true).build();
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of(schedule));

        cardService.showScheduleEditByType(
                user, 10L, TaskType.MISTING, null, PlantCardService.BACK_TO_LOCATION_PREFIX + "3", client);

        String text = sentText();
        assertThat(text).contains("💨 *Опрыскивание*");
        List<String> callbacks = buttonCallbacks();
        assertThat(callbacks).contains(
                "PLANT:SCHED:INTERVAL:10:MISTING:LOC:3",
                "PLANT:SCHED:POSTPONE:10:MISTING:0:LOC:3",
                "PLANT:SCHED:POSTPONE:10:MISTING:1:LOC:3",
                "PLANT:SCHED:POSTPONE:10:MISTING:3:LOC:3",
                "PLANT:SCHED:POSTPONE:10:MISTING:7:LOC:3",
                "PLANT:SETTINGS:10:LOC:3");
    }

    // ==================================================================
    // showCareTypesScreen
    // ==================================================================

    @Test
    void should_send_not_found_message_when_plant_missing_on_care_types() {
        User user = givenUser(1L, 555L);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.empty());

        cardService.showCareTypesScreen(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        assertThat(sentText()).contains("❌ Растение не найдено.");
    }

    @Test
    void should_render_active_inactive_and_unconfigured_states_for_all_task_types() {
        User user = givenUser(1L, 555L);
        Plant plant = givenPlant(10L, user);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));

        CareSchedule activeWatering = CareSchedule.builder()
                .taskType(TaskType.WATERING).intervalDays(7).active(true).build();
        CareSchedule inactiveMisting = CareSchedule.builder()
                .taskType(TaskType.MISTING).intervalDays(2).active(false).build();
        // FERTILIZING и SOIL_CHECK остаются не настроены.
        when(plantService.getAllSchedules(10L)).thenReturn(List.of(activeWatering, inactiveMisting));

        cardService.showCareTypesScreen(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        String text = sentText();
        assertThat(text).contains("💧 Полив: ✅ каждые 7 дн.");
        assertThat(text).contains("💨 Опрыскивание: ❌ выключено (было каждые 2 дн.)");
        assertThat(text).contains("🌿 Удобрение: ➖ не настроено");
        assertThat(text).contains("🪴 Проверка грунта: ➖ не настроено");

        List<String> buttonTexts = buttonTexts();
        assertThat(buttonTexts).contains("❌ Выключить Полив");
        assertThat(buttonTexts).contains("✅ Включить Опрыскивание");
        assertThat(buttonTexts).contains("✅ Включить Удобрение");
    }

    // ==================================================================
    // cycleSeasonalOverride
    // ==================================================================

    @Test
    void should_not_touch_telegram_when_plant_missing_on_cycle_seasonal_override() {
        User user = givenUser(1L, 555L);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.empty());

        cardService.cycleSeasonalOverride(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        verifyNoInteractions(client);
        verify(plantRepository, never()).save(any());
    }

    @Test
    void should_cycle_inherit_to_on_when_current_override_is_null() {
        User user = givenUser(1L, 555L);
        Plant plant = givenPlant(10L, user);
        plant.setSeasonalOverride(null);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));
        when(plantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of());

        cardService.cycleSeasonalOverride(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        var captor = ArgumentCaptor.forClass(Plant.class);
        verify(plantRepository).save(captor.capture());
        assertThat(captor.getValue().getSeasonalOverride()).isEqualTo(SeasonalOverride.ON);
        assertThat(buttonTexts()).contains("🍂 Сезонность: Включена");
    }

    @Test
    void should_cycle_on_to_off() {
        User user = givenUser(1L, 555L);
        Plant plant = givenPlant(10L, user);
        plant.setSeasonalOverride(SeasonalOverride.ON);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));
        when(plantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of());

        cardService.cycleSeasonalOverride(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        assertThat(plant.getSeasonalOverride()).isEqualTo(SeasonalOverride.OFF);
        assertThat(buttonTexts()).contains("🍂 Сезонность: Выключена");
    }

    @Test
    void should_cycle_off_back_to_inherit() {
        User user = givenUser(1L, 555L);
        Plant plant = givenPlant(10L, user);
        plant.setSeasonalOverride(SeasonalOverride.OFF);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));
        when(plantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of());

        cardService.cycleSeasonalOverride(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        assertThat(plant.getSeasonalOverride()).isEqualTo(SeasonalOverride.INHERIT);
        assertThat(buttonTexts()).contains("🍂 Сезонность: Наследовать");
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

    private Plant givenPlant(Long id, User user) {
        var p = new Plant();
        p.setName("Моя монстера");
        p.setUser(user);
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

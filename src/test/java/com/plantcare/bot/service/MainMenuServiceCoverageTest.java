package com.plantcare.bot.service;

import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.service.CareHistoryService;
import com.plantcare.core.service.LocationService;
import com.plantcare.core.weather.dto.HumidityInfo;
import com.plantcare.core.weather.dto.WeatherRecommendation;
import com.plantcare.core.weather.service.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Дополнительное покрытие {@link MainMenuService}: активная локация (пауза/смена),
 * группировка задач по нескольким локациям и погодная подсказка (issue #69, #70).
 * Не дублирует {@link MainMenuServiceTest} и {@link MainMenuServiceVacationBannerTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MainMenuService — активная локация, группировка, погода")
class MainMenuServiceCoverageTest {

    @Mock private PlantRepository plantRepository;
    @Mock private CareScheduleRepository careScheduleRepository;
    @Mock private LocationService locationService;
    @Mock private CareHistoryService careHistoryService;
    @Mock private TelegramClient telegramClient;
    @Mock private WeatherService weatherService;

    private MainMenuService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-05-22T12:00:00Z"), ZoneOffset.UTC);

        service = new MainMenuService(
                plantRepository,
                careScheduleRepository,
                locationService,
                careHistoryService,
                fixedClock,
                weatherService
        );

        lenient().when(careHistoryService.computeUserStreak(any(), any())).thenReturn(0);
        lenient().when(plantRepository.countByUserIdAndArchivedAtIsNull(any())).thenReturn(0L);
    }

    private Location buildLocation(long id, String name, boolean isDefault, LocalDateTime createdAt) {
        Location location = Location.builder()
                .name(name)
                .defaultLocation(isDefault)
                .build();
        ReflectionTestUtils.setField(location, "id", id);
        ReflectionTestUtils.setField(location, "createdAt", createdAt);
        return location;
    }

    @Test
    @DisplayName("Активная локация на паузе — показывает баннер, кнопку «Возобновить» и скрывает задачи")
    void should_show_pause_banner_and_hide_tasks_when_active_location_paused() throws TelegramApiException {
        Location paused = buildLocation(7L, "Балкон", false, LocalDateTime.now().minusDays(10));
        paused.setPausedUntil(Instant.now().plusSeconds(3600));

        User user = User.builder()
                .telegramChatId(200L)
                .timezone("Europe/Riga")
                .activeLocation(paused)
                .build();

        Plant plant = Plant.builder().user(user).name("Монстера").location(paused).build();
        CareSchedule schedule = CareSchedule.builder()
                .plant(plant)
                .taskType(TaskType.WATERING)
                .nextDueAt(LocalDateTime.now().minusHours(1))
                .active(true)
                .build();

        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of(schedule));
        when(locationService.getUserLocations(any())).thenReturn(List.of(paused));

        service.sendMainMenu(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        SendMessage sent = captor.getValue();

        assertThat(sent.getText())
                .contains("⏸")
                .contains("на паузе до")
                .contains("Уведомления по этой локации приостановлены 🌙")
                .doesNotContain("Монстера");

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sent.getReplyMarkup();
        List<String> callbacks = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
        List<String> texts = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getText)
                .toList();

        assertThat(callbacks).contains("LOC_RESUME:7");
        assertThat(texts).anyMatch(t -> t.startsWith("▶️ Возобновить"));
    }

    @Test
    @DisplayName("Активная локация не на паузе — заголовок локации и кнопка «Сменить», без «Возобновить»")
    void should_show_active_location_header_and_switch_button_when_not_paused() throws TelegramApiException {
        Location active = buildLocation(3L, "Кухня", false, LocalDateTime.now().minusDays(5));

        User user = User.builder()
                .telegramChatId(201L)
                .timezone("Europe/Riga")
                .activeLocation(active)
                .build();

        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of());
        when(locationService.getUserLocations(any())).thenReturn(List.of(active));

        service.sendMainMenu(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        SendMessage sent = captor.getValue();

        assertThat(sent.getText())
                .contains("📍 Локация: *" + active.getDisplayName() + "*")
                .doesNotContain("на паузе до");

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sent.getReplyMarkup();
        List<String> callbacks = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
        List<String> texts = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getText)
                .toList();

        assertThat(callbacks).contains("LOC_SWITCH_LIST");
        assertThat(texts).anyMatch(t -> t.contains("[Сменить]"));
        assertThat(texts).noneMatch(t -> t.startsWith("▶️ Возобновить"));
    }

    @Test
    @DisplayName("Активная локация задана — задачи из других локаций отфильтровываются")
    void should_filter_tasks_by_active_location() throws TelegramApiException {
        Location active = buildLocation(1L, "Спальня", false, LocalDateTime.now().minusDays(1));
        Location other = buildLocation(2L, "Кухня", false, LocalDateTime.now().minusDays(1));

        User user = User.builder()
                .telegramChatId(202L)
                .timezone("Europe/Riga")
                .activeLocation(active)
                .build();

        Plant inActive = Plant.builder().user(user).name("Фикус").location(active).build();
        Plant inOther = Plant.builder().user(user).name("Кактус").location(other).build();

        CareSchedule scheduleActive = CareSchedule.builder()
                .plant(inActive).taskType(TaskType.WATERING)
                .nextDueAt(LocalDateTime.now().minusHours(1)).active(true).build();
        CareSchedule scheduleOther = CareSchedule.builder()
                .plant(inOther).taskType(TaskType.MISTING)
                .nextDueAt(LocalDateTime.now().minusHours(1)).active(true).build();

        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any()))
                .thenReturn(List.of(scheduleActive, scheduleOther));
        when(locationService.getUserLocations(any())).thenReturn(List.of(active, other));

        service.sendMainMenu(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        String text = captor.getValue().getText();
        assertThat(text).contains("Фикус").contains("полить");
        assertThat(text).doesNotContain("Кактус");
    }

    @Test
    @DisplayName("Без активной локации, несколько локаций — задачи группируются по локации")
    void should_group_tasks_by_location_when_no_active_location_and_multiple_locations() throws TelegramApiException {
        Location locA = buildLocation(10L, "Гостиная", false, LocalDateTime.now().minusDays(3));
        Location locB = buildLocation(11L, "Спальня", true, LocalDateTime.now().minusDays(2));
        Location locEmpty = buildLocation(12L, "Балкон", false, LocalDateTime.now().minusDays(1));

        User user = User.builder()
                .telegramChatId(203L)
                .timezone("Europe/Riga")
                .build();

        Plant plantA = Plant.builder().user(user).name("Пальма").location(locA).build();
        Plant plantB = Plant.builder().user(user).name("Орхидея").location(locB).build();
        Plant plantNoLoc = Plant.builder().user(user).name("Безлокационный").location(null).build();

        CareSchedule scheduleA = CareSchedule.builder()
                .plant(plantA).taskType(TaskType.WATERING)
                .nextDueAt(LocalDateTime.now().minusHours(1)).active(true).build();
        CareSchedule scheduleB = CareSchedule.builder()
                .plant(plantB).taskType(TaskType.FERTILIZING)
                .nextDueAt(LocalDateTime.now().minusHours(1)).active(true).build();
        CareSchedule scheduleNoLoc = CareSchedule.builder()
                .plant(plantNoLoc).taskType(TaskType.SOIL_CHECK)
                .nextDueAt(LocalDateTime.now().minusHours(1)).active(true).build();

        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any()))
                .thenReturn(List.of(scheduleA, scheduleB, scheduleNoLoc));
        when(locationService.getUserLocations(any())).thenReturn(List.of(locA, locB, locEmpty));

        service.sendMainMenu(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        String text = captor.getValue().getText();
        assertThat(text).contains(locA.getDisplayName()).contains("Пальма").contains("полить");
        assertThat(text).contains(locB.getDisplayName()).contains("Орхидея").contains("удобрить");
        // Локация без задач не должна попасть заголовком в текст.
        assertThat(text).doesNotContain(locEmpty.getDisplayName());
        // Растение без локации в группированном режиме пропускается (не падает, не рендерится).
        assertThat(text).doesNotContain("Безлокационный");
    }

    @Test
    @DisplayName("Погода включена и есть задача полива — подсказка о влажности добавляется в конец меню")
    void should_append_weather_hint_when_usable_and_watering_task_present() throws TelegramApiException {
        User user = User.builder()
                .telegramChatId(204L)
                .timezone("Europe/Riga")
                .weatherEnabled(true)
                .weatherLat(56.9)
                .weatherLon(24.1)
                .build();

        Plant plant = Plant.builder().user(user).name("Кротон").build();
        CareSchedule schedule = CareSchedule.builder()
                .plant(plant).taskType(TaskType.WATERING)
                .nextDueAt(LocalDateTime.now().minusHours(1)).active(true).build();

        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of(schedule));
        when(locationService.getUserLocations(any())).thenReturn(List.of());

        HumidityInfo info = new HumidityInfo(82, WeatherRecommendation.DEFER_OK, LocalDateTime.now(), false);
        when(weatherService.getCurrentHumidity(user)).thenReturn(Optional.of(info));

        service.sendMainMenu(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("🌦 Влажность 82% — можно отложить, если земля ещё влажная");
    }

    @Test
    @DisplayName("Погода включена, но задач полива нет — подсказка не запрашивается и не добавляется")
    void should_not_append_weather_hint_when_no_watering_task() throws TelegramApiException {
        User user = User.builder()
                .telegramChatId(205L)
                .timezone("Europe/Riga")
                .weatherEnabled(true)
                .weatherLat(56.9)
                .weatherLon(24.1)
                .build();

        Plant plant = Plant.builder().user(user).name("Кротон").build();
        CareSchedule schedule = CareSchedule.builder()
                .plant(plant).taskType(TaskType.MISTING)
                .nextDueAt(LocalDateTime.now().minusHours(1)).active(true).build();

        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of(schedule));
        when(locationService.getUserLocations(any())).thenReturn(List.of());

        service.sendMainMenu(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText()).doesNotContain("🌦");
        verify(weatherService, never()).getCurrentHumidity(any());
    }

    @Test
    @DisplayName("Погода не настроена (opt-out) — подсказка не добавляется, weatherService не вызывается")
    void should_not_append_weather_hint_when_weather_not_usable() throws TelegramApiException {
        User user = User.builder()
                .telegramChatId(206L)
                .timezone("Europe/Riga")
                .weatherEnabled(false)
                .build();

        Plant plant = Plant.builder().user(user).name("Кротон").build();
        CareSchedule schedule = CareSchedule.builder()
                .plant(plant).taskType(TaskType.WATERING)
                .nextDueAt(LocalDateTime.now().minusHours(1)).active(true).build();

        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of(schedule));
        when(locationService.getUserLocations(any())).thenReturn(List.of());

        service.sendMainMenu(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText()).doesNotContain("🌦");
        verify(weatherService, never()).getCurrentHumidity(any());
    }

    @Test
    @DisplayName("Некорректная таймзона юзера при паузе локации — фолбэк на UTC вместо падения (Asia/Almaty кейс рядом)")
    void should_fallback_to_utc_when_user_timezone_invalid_for_pause_date() throws TelegramApiException {
        Location paused = buildLocation(9L, "Терраса", false, LocalDateTime.now().minusDays(1));
        // Location.isPaused() сравнивает с Instant.now() (не с инжектируемым Clock),
        // поэтому дата паузы обязана быть в будущем, иначе баннер вообще не рендерится.
        paused.setPausedUntil(Instant.parse("2099-06-01T00:00:00Z"));

        User user = User.builder()
                .telegramChatId(207L)
                .timezone("Not/AZone")
                .activeLocation(paused)
                .build();

        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of());
        when(locationService.getUserLocations(any())).thenReturn(List.of(paused));

        service.sendMainMenu(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        // UTC-фолбэк форматирует ту же дату паузы как 01.06.2099.
        assertThat(captor.getValue().getText()).contains("на паузе до 01.06.2099");
    }

    @Test
    @DisplayName("Пользователь в Asia/Almaty с активной локацией на паузе — дата паузы форматируется в его зоне")
    void should_format_pause_date_in_non_utc_user_timezone() throws TelegramApiException {
        Location paused = buildLocation(13L, "Лоджия", false, LocalDateTime.now().minusDays(1));
        // 2099-05-31T20:00:00Z => 2099-06-01T01:00 в Asia/Almaty (UTC+5), т.е. другая календарная
        // дата, чем в UTC (31.05) — именно это и доказывает форматирование в зоне юзера.
        // Дата в будущем обязательна: Location.isPaused() сравнивает с Instant.now().
        paused.setPausedUntil(Instant.parse("2099-05-31T20:00:00Z"));

        User user = User.builder()
                .telegramChatId(208L)
                .timezone("Asia/Almaty")
                .activeLocation(paused)
                .build();

        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of());
        when(locationService.getUserLocations(any())).thenReturn(List.of(paused));

        service.sendMainMenu(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText()).contains("на паузе до 01.06.2099");
    }
}

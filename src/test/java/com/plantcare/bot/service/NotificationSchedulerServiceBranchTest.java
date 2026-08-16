package com.plantcare.bot.service;

import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.metrics.MetricsService;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.NotificationDigestRepository;
import com.plantcare.core.repository.NotificationLogRepository;
import com.plantcare.core.repository.UserDeviceRepository;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.core.service.LocationSharingService;
import com.plantcare.core.service.QuietHoursPolicy;
import com.plantcare.core.service.SchedulerHealthTracker;
import com.plantcare.core.weather.dto.HumidityInfo;
import com.plantcare.core.weather.dto.WeatherRecommendation;
import com.plantcare.core.weather.service.WeatherService;
import com.plantcare.core.seasonal.service.SeasonalIntervalService;
import com.plantcare.bot.telegram.RateLimitedTelegramSender;
import com.plantcare.bot.telegram.SendCallbacks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Дополнительные branch-тесты {@link NotificationSchedulerService}, не покрытые
 * {@link NotificationSchedulerServiceTest} (см. этот файл первым за harness'ом):
 * ручные админ-эндпоинты {@code sendOneSchedule}/{@code skipOneSchedule}, роутинг
 * SOIL_CHECK/акклиматизации внутри тика, устойчивость тика к ошибке на одном
 * пользователе, fan-out caretaker'ам и погодная подсказка.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotificationSchedulerService — branch-покрытие")
class NotificationSchedulerServiceBranchTest {

    @Mock private CareScheduleRepository careScheduleRepository;
    @Mock private NotificationLogRepository notificationLogRepository;
    @Mock private NotificationDigestRepository notificationDigestRepository;
    @Mock private UserRepository userRepository;
    @Mock private SchedulerHealthTracker schedulerHealthTracker;
    @Mock private WeatherService weatherService;
    @Mock private SeasonalIntervalService seasonalIntervalService;
    @Mock private QuietHoursPolicy quietHoursPolicy;
    @Mock private MetricsService metricsService;
    @Mock private RateLimitedTelegramSender telegramSender;
    @Mock private NotificationDeliveryCallbacks deliveryCallbacks;
    @Mock private LocationSharingService locationSharingService;
    @Mock private UserDeviceRepository userDeviceRepository;
    @Mock private PushFanOutService pushFanOutService;

    @org.mockito.Spy
    private ReminderKeyboardFactory reminderKeyboardFactory = new ReminderKeyboardFactory();

    @org.mockito.Spy
    private Clock clock = Clock.fixed(Instant.parse("2026-05-24T12:00:00Z"), ZoneOffset.UTC);

    @InjectMocks
    private NotificationSchedulerService service;

    private User user;
    private Plant plant;
    private CareSchedule schedule;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(100L)
                .timezone("Europe/Minsk")
                .quietHoursStart(LocalTime.of(0, 0))
                .quietHoursEnd(LocalTime.of(0, 0))
                .blocked(false)
                .build();
        ReflectionTestUtils.setField(user, "id", 1L);

        plant = Plant.builder().user(user).name("Монстера").build();
        ReflectionTestUtils.setField(plant, "id", 10L);

        schedule = CareSchedule.builder()
                .plant(plant)
                .taskType(TaskType.WATERING)
                .intervalDays(7)
                .nextDueAt(LocalDateTime.now().minusHours(1))
                .active(true)
                .build();
        ReflectionTestUtils.setField(schedule, "id", 100L);

        when(quietHoursPolicy.isQuiet(any(User.class), any(Instant.class))).thenReturn(false);
        when(seasonalIntervalService.effectiveIntervalDays(any(), any(), any(Integer.class)))
                .thenAnswer(inv -> inv.getArgument(2, Integer.class));
        when(userDeviceRepository.findByUserId(any())).thenReturn(List.of());
        when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                .thenReturn(false);
    }

    // ===== sendOneSchedule (admin manual trigger, issue #59) =====

    @Nested
    @DisplayName("sendOneSchedule — ручной пуш из админки")
    class SendOneSchedule {

        @Test
        @DisplayName("Расписание не найдено → notFound(), никаких side-эффектов")
        void should_return_not_found_when_schedule_missing() {
            when(careScheduleRepository.findById(999L)).thenReturn(Optional.empty());

            NotificationSchedulerService.SendOneResult result = service.sendOneSchedule(999L, false);

            assertThat(result.isNotFound()).isTrue();
            verify(telegramSender, never()).enqueue(any(SendMessage.class), any(SendCallbacks.class));
        }

        @Test
        @DisplayName("Расписание неактивно → skipped('Расписание неактивно')")
        void should_return_skipped_when_schedule_inactive() {
            schedule.setActive(false);
            when(careScheduleRepository.findById(100L)).thenReturn(Optional.of(schedule));

            NotificationSchedulerService.SendOneResult result = service.sendOneSchedule(100L, false);

            assertThat(result.isSkipped()).isTrue();
            assertThat(result.reason()).isEqualTo("Расписание неактивно");
            verifyNoTelegramInteraction();
        }

        @Test
        @DisplayName("Растение архивировано → skipped('Растение архивировано')")
        void should_return_skipped_when_plant_archived() {
            plant.archive();
            when(careScheduleRepository.findById(100L)).thenReturn(Optional.of(schedule));

            NotificationSchedulerService.SendOneResult result = service.sendOneSchedule(100L, false);

            assertThat(result.isSkipped()).isTrue();
            assertThat(result.reason()).isEqualTo("Растение архивировано");
            verifyNoTelegramInteraction();
        }

        @Test
        @DisplayName("Юзер заблокирован → skipped('Юзер заблокирован')")
        void should_return_skipped_when_user_blocked() {
            user.setBlocked(true);
            when(careScheduleRepository.findById(100L)).thenReturn(Optional.of(schedule));

            NotificationSchedulerService.SendOneResult result = service.sendOneSchedule(100L, false);

            assertThat(result.isSkipped()).isTrue();
            assertThat(result.reason()).isEqualTo("Юзер заблокирован");
            verifyNoTelegramInteraction();
        }

        @Test
        @DisplayName("force=false и юзер на паузе (shouldSend=false) → skipped фильтром")
        void should_return_skipped_when_not_forced_and_shouldSend_false() {
            user.setPausedUntil(LocalDateTime.now().plusHours(1));
            when(careScheduleRepository.findById(100L)).thenReturn(Optional.of(schedule));

            NotificationSchedulerService.SendOneResult result = service.sendOneSchedule(100L, false);

            assertThat(result.isSkipped()).isTrue();
            assertThat(result.reason()).isEqualTo("Заблокировано фильтром (пауза/quiet-hours/дедуп)");
            verifyNoTelegramInteraction();
        }

        @Test
        @DisplayName("force=true игнорирует паузу и quiet-hours: push всё равно уходит")
        void should_send_when_forced_even_if_paused() {
            user.setPausedUntil(LocalDateTime.now().plusHours(1));
            when(quietHoursPolicy.isQuiet(any(User.class), any(Instant.class))).thenReturn(true);
            when(careScheduleRepository.findById(100L)).thenReturn(Optional.of(schedule));

            NotificationSchedulerService.SendOneResult result = service.sendOneSchedule(100L, true);

            assertThat(result.isSent()).isTrue();
            verify(telegramSender).enqueue(any(SendMessage.class), any(SendCallbacks.class));
            verify(careScheduleRepository).save(schedule);
            assertThat(schedule.getNextDueAt()).isAfter(LocalDateTime.now().plusDays(6));
        }

        @Test
        @DisplayName("Успех (не форс): sent(), next_due_at продвинут с сезонной корректировкой, save вызван")
        void should_send_and_advance_next_due_at_when_not_forced_and_allowed() {
            when(careScheduleRepository.findById(100L)).thenReturn(Optional.of(schedule));
            when(seasonalIntervalService.effectiveIntervalDays(eq(plant), eq(user), eq(7))).thenReturn(3);

            NotificationSchedulerService.SendOneResult result = service.sendOneSchedule(100L, false);

            assertThat(result.isSent()).isTrue();
            verify(careScheduleRepository).save(schedule);
            assertThat(schedule.getNextDueAt())
                    .isCloseTo(LocalDateTime.now().plusDays(3), org.assertj.core.api.Assertions
                            .within(2, java.time.temporal.ChronoUnit.MINUTES));
        }

        @Test
        @DisplayName("Исключение при отправке → failed(message), исходное исключение не всплывает")
        void should_return_failed_when_sending_throws() {
            when(careScheduleRepository.findById(100L)).thenReturn(Optional.of(schedule));
            doThrow(new RuntimeException("boom")).when(careScheduleRepository).save(any());

            NotificationSchedulerService.SendOneResult result = service.sendOneSchedule(100L, false);

            assertThat(result.isFailed()).isTrue();
            assertThat(result.reason()).isEqualTo("boom");
        }

        private void verifyNoTelegramInteraction() {
            verify(telegramSender, never()).enqueue(any(SendMessage.class), any(SendCallbacks.class));
        }
    }

    // ===== skipOneSchedule (admin manual skip, issue #59) =====

    @Nested
    @DisplayName("skipOneSchedule — ручной пропуск из админки")
    class SkipOneSchedule {

        @Test
        @DisplayName("Расписание не найдено → false")
        void should_return_false_when_schedule_missing() {
            when(careScheduleRepository.findById(999L)).thenReturn(Optional.empty());

            boolean result = service.skipOneSchedule(999L);

            assertThat(result).isFalse();
            verify(careScheduleRepository, never()).save(any());
        }

        @Test
        @DisplayName("Расписание найдено → true, next_due_at продвинут с сезонной корректировкой")
        void should_advance_next_due_at_with_seasonal_adjustment_when_found() {
            when(careScheduleRepository.findById(100L)).thenReturn(Optional.of(schedule));
            when(seasonalIntervalService.effectiveIntervalDays(eq(plant), eq(user), eq(7))).thenReturn(10);

            boolean result = service.skipOneSchedule(100L);

            assertThat(result).isTrue();
            verify(careScheduleRepository).save(schedule);
            assertThat(schedule.getNextDueAt())
                    .isCloseTo(LocalDateTime.now().plusDays(10), org.assertj.core.api.Assertions
                            .within(2, java.time.temporal.ChronoUnit.MINUTES));
        }
    }

    // ===== executeTick: SOIL_CHECK / acclimation standalone routing =====

    @Nested
    @DisplayName("Тик: standalone-роутинг SOIL_CHECK и WATERING+акклиматизация")
    class StandaloneRouting {

        @Test
        @DisplayName("SOIL_CHECK всегда шлётся отдельным пушем, не попадает в digest")
        void should_send_soil_check_standalone_even_with_other_due_tasks() {
            CareSchedule soilCheck = CareSchedule.builder()
                    .plant(plant).taskType(TaskType.SOIL_CHECK).intervalDays(3)
                    .nextDueAt(LocalDateTime.now().minusHours(1)).active(true).build();
            ReflectionTestUtils.setField(soilCheck, "id", 101L);
            CareSchedule misting = CareSchedule.builder()
                    .plant(plant).taskType(TaskType.MISTING).intervalDays(3)
                    .nextDueAt(LocalDateTime.now().minusHours(1)).active(true).build();
            ReflectionTestUtils.setField(misting, "id", 102L);

            when(careScheduleRepository.findDueSchedules(any()))
                    .thenReturn(List.of(soilCheck, misting));
            when(notificationDigestRepository.save(any())).thenAnswer(inv -> {
                var d = inv.getArgument(0, com.plantcare.core.domain.NotificationDigest.class);
                ReflectionTestUtils.setField(d, "id", 500L);
                return d;
            });

            service.checkAndSendNotifications();

            // SOIL_CHECK не попал в digest — только misting одна задача, а soil-check отдельно.
            // Итого два независимых enqueue, ни один не является digest (нет "На сегодня:").
            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender, times(2)).enqueue(captor.capture(), any(SendCallbacks.class));
            List<SendMessage> sent = captor.getAllValues();
            assertThat(sent).noneMatch(m -> m.getText().contains("На сегодня:"));
            assertThat(sent).anyMatch(m -> m.getText().contains("Проверь грунт"));
            assertThat(sent).anyMatch(m -> m.getText().contains("Пора опрыскать"));
            verify(notificationDigestRepository, never()).save(any());
        }

        @Test
        @DisplayName("WATERING у растения в акклиматизации шлётся отдельным soft-промптом с 3 кнопками")
        void should_send_acclimation_watering_prompt_when_plant_in_acclimation() {
            plant.setAcclimationUntil(LocalDateTime.now().plusDays(3));

            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender).enqueue(captor.capture(), any(SendCallbacks.class));
            assertThat(captor.getValue().getText()).contains("Проверь грунт — сухо на 2–3 см?");

            var keyboard = (org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup)
                    captor.getValue().getReplyMarkup();
            List<String> callbackData = keyboard.getKeyboard().stream()
                    .flatMap(java.util.Collection::stream)
                    .map(org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton::getCallbackData)
                    .toList();
            assertThat(callbackData).contains(
                    "v1:accl_soil:100:DRY", "v1:accl_soil:100:WET", "v1:accl_soil:100:UNKNOWN");
        }

        @Test
        @DisplayName("Акклиматизация не применяется к MISTING — идёт обычным путём")
        void should_not_apply_acclimation_prompt_to_non_watering_task() {
            plant.setAcclimationUntil(LocalDateTime.now().plusDays(3));
            schedule.setTaskType(TaskType.MISTING);

            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender).enqueue(captor.capture(), any(SendCallbacks.class));
            assertThat(captor.getValue().getText()).contains("Пора опрыскать");
        }
    }

    // ===== executeTick: устойчивость к ошибкам на одном пользователе/расписании =====

    @Nested
    @DisplayName("Тик устойчив к ошибке на одном пользователе — остальные всё равно обрабатываются")
    class TickResilience {

        @Test
        @DisplayName("shouldSend бросает для одного расписания — остальные пользователи всё равно обрабатываются")
        void should_continue_tick_when_shouldSend_throws_for_one_schedule() {
            User secondUser = User.builder()
                    .telegramChatId(200L).timezone("Europe/Minsk")
                    .quietHoursStart(LocalTime.of(0, 0)).quietHoursEnd(LocalTime.of(0, 0))
                    .blocked(false).build();
            ReflectionTestUtils.setField(secondUser, "id", 2L);
            Plant secondPlant = Plant.builder().user(secondUser).name("Фикус").build();
            ReflectionTestUtils.setField(secondPlant, "id", 20L);
            CareSchedule secondSchedule = CareSchedule.builder()
                    .plant(secondPlant).taskType(TaskType.WATERING).intervalDays(3)
                    .nextDueAt(LocalDateTime.now().minusHours(1)).active(true).build();
            ReflectionTestUtils.setField(secondSchedule, "id", 200L);

            when(careScheduleRepository.findDueSchedules(any()))
                    .thenReturn(List.of(schedule, secondSchedule));
            // Дедуп-проверка бросает только для первого plant.id — второй проходит штатно.
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(
                    eq(10L), any(), any())).thenThrow(new RuntimeException("DB hiccup"));

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender, times(1)).enqueue(captor.capture(), any(SendCallbacks.class));
            assertThat(captor.getValue().getChatId()).isEqualTo("200");
            // Тик всё равно считается завершённым успешно (ошибка изолирована в per-schedule try/catch).
            verify(schedulerHealthTracker).recordTick();
        }

        @Test
        @DisplayName("Отправка бросает для одной группы — остальные группы всё равно обрабатываются")
        void should_continue_tick_when_send_throws_for_one_user_group() {
            User secondUser = User.builder()
                    .telegramChatId(200L).timezone("Europe/Minsk")
                    .quietHoursStart(LocalTime.of(0, 0)).quietHoursEnd(LocalTime.of(0, 0))
                    .blocked(false).build();
            ReflectionTestUtils.setField(secondUser, "id", 2L);
            Plant secondPlant = Plant.builder().user(secondUser).name("Фикус").build();
            ReflectionTestUtils.setField(secondPlant, "id", 20L);
            CareSchedule secondSchedule = CareSchedule.builder()
                    .plant(secondPlant).taskType(TaskType.WATERING).intervalDays(3)
                    .nextDueAt(LocalDateTime.now().minusHours(1)).active(true).build();
            ReflectionTestUtils.setField(secondSchedule, "id", 200L);

            when(careScheduleRepository.findDueSchedules(any()))
                    .thenReturn(List.of(schedule, secondSchedule));
            // Первый enqueue (chat 100) бросает, второй (chat 200) — штатно.
            doThrow(new RuntimeException("telegram exploded"))
                    .doNothing()
                    .when(telegramSender).enqueue(any(SendMessage.class), any(SendCallbacks.class));

            service.checkAndSendNotifications();

            verify(telegramSender, times(2)).enqueue(any(SendMessage.class), any(SendCallbacks.class));
            verify(schedulerHealthTracker).recordTick();
        }
    }

    // ===== Fan-out caretaker'ам локации (issue #77) =====

    @Nested
    @DisplayName("Fan-out caretaker'ам локации (issue #77)")
    class CaretakerFanOut {

        @Test
        @DisplayName("Одиночное уведомление: caretaker'ы локации получают ту же копию push'а")
        void should_fan_out_single_notification_to_location_caretakers() {
            Location location = Location.builder()
                    .user(user).name("Дача").emoji("🏡").defaultLocation(false).build();
            ReflectionTestUtils.setField(location, "id", 55L);
            plant.setLocation(location);

            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(locationSharingService.caretakerChatIdsForLocation(55L))
                    .thenReturn(List.of(300L, 301L));

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender, times(3)).enqueue(captor.capture(), any(SendCallbacks.class));
            List<String> chatIds = captor.getAllValues().stream()
                    .map(SendMessage::getChatId).toList();
            assertThat(chatIds).containsExactlyInAnyOrder("100", "300", "301");
        }

        @Test
        @DisplayName("Дайджест: caretaker'ы всех затронутых локаций получают копию дайджеста")
        void should_fan_out_digest_to_location_caretakers() {
            Location location = Location.builder()
                    .user(user).name("Дача").emoji("🏡").defaultLocation(false).build();
            ReflectionTestUtils.setField(location, "id", 66L);
            plant.setLocation(location);

            CareSchedule misting = CareSchedule.builder()
                    .plant(plant).taskType(TaskType.MISTING).intervalDays(3)
                    .nextDueAt(LocalDateTime.now().minusHours(1)).active(true).build();
            ReflectionTestUtils.setField(misting, "id", 103L);

            when(careScheduleRepository.findDueSchedules(any()))
                    .thenReturn(List.of(schedule, misting));
            when(notificationDigestRepository.save(any())).thenAnswer(inv -> {
                var d = inv.getArgument(0, com.plantcare.core.domain.NotificationDigest.class);
                ReflectionTestUtils.setField(d, "id", 501L);
                return d;
            });
            when(locationSharingService.caretakerChatIdsForLocation(66L)).thenReturn(List.of(400L));

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender, times(2)).enqueue(captor.capture(), any(SendCallbacks.class));
            List<String> chatIds = captor.getAllValues().stream().map(SendMessage::getChatId).toList();
            assertThat(chatIds).containsExactlyInAnyOrder("100", "400");
            assertThat(captor.getAllValues()).allMatch(m -> m.getText().contains("На сегодня:"));
        }
    }

    // ===== Погодная подсказка (issue #69) =====

    @Nested
    @DisplayName("Погодная подсказка к тексту полива (issue #69)")
    class WeatherHint {

        @Test
        @DisplayName("Не WATERING → weatherService не вызывается, текст не меняется")
        void should_not_call_weather_service_for_non_watering_task() {
            schedule.setTaskType(TaskType.MISTING);
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));

            service.checkAndSendNotifications();

            verify(weatherService, never()).getCurrentHumidity(any());
        }

        @Test
        @DisplayName("WATERING, но погода не настроена (isWeatherUsable=false) → без подсказки")
        void should_skip_weather_hint_when_not_usable() {
            // user по умолчанию weatherEnabled=false — isWeatherUsable() уже false.
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));

            service.checkAndSendNotifications();

            verify(weatherService, never()).getCurrentHumidity(any());
            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender).enqueue(captor.capture(), any(SendCallbacks.class));
            assertThat(captor.getValue().getText()).doesNotContain("Влажность");
        }

        @Test
        @DisplayName("WATERING, погода usable, сервис вернул подсказку → текст дополнен строкой влажности")
        void should_append_weather_hint_when_service_returns_value() {
            user.setWeatherEnabled(true);
            user.setWeatherLat(53.9);
            user.setWeatherLon(27.5);
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            HumidityInfo info = new HumidityInfo(82, WeatherRecommendation.DEFER_OK,
                    LocalDateTime.now(), false);
            when(weatherService.getCurrentHumidity(user)).thenReturn(Optional.of(info));

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender).enqueue(captor.capture(), any(SendCallbacks.class));
            assertThat(captor.getValue().getText()).contains("Влажность 82%");
        }

        @Test
        @DisplayName("WATERING, погода usable, сервис вернул Optional.empty() → текст не меняется")
        void should_not_append_weather_hint_when_service_returns_empty() {
            user.setWeatherEnabled(true);
            user.setWeatherLat(53.9);
            user.setWeatherLon(27.5);
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(weatherService.getCurrentHumidity(user)).thenReturn(Optional.empty());

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender).enqueue(captor.capture(), any(SendCallbacks.class));
            assertThat(captor.getValue().getText()).doesNotContain("Влажность");
        }
    }
}

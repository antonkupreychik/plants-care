package com.plantcare.bot.service;

import com.plantcare.bot.client.TelegramClientProvider;
import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareScheduleRepository;
import com.plantcare.bot.repository.LocationRepository;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.repository.UserRepository;
import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.bot.telegram.RateLimitedTelegramSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Интеграционные тесты hourly-тика отпуска (issue #53) после перевода на
 * rate-limited очередь (issue #29): tomorrow-back за ~24 часа до конца +
 * welcome-back при окончании.
 *
 * <p>Clock зафиксирован на 2026-05-23T10:00:00Z, поэтому окно поиска
 * tomorrow-back = [11:00; 12:00) того же дня (now+23h..now+24h).
 *
 * <p>Отправка теперь идёт fire-and-forget через {@link RateLimitedTelegramSender}
 * (без колбэков), поэтому проверяем вызовы {@code enqueue(SendMessage)} вместо
 * прямого {@code TelegramClient.execute}.
 */
@Import(VacationSchedulerServiceTest.FixedClockConfig.class)
@DisplayName("VacationSchedulerService (issue #53 / #29)")
class VacationSchedulerServiceTest extends IntegrationTestBase {

    static final Instant FIXED_NOW = Instant.parse("2026-05-23T10:00:00Z");
    static final LocalDateTime NOW_LDT = LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);

    @Autowired private VacationSchedulerService scheduler;
    @Autowired private UserRepository userRepository;
    @Autowired private PlantRepository plantRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private CareScheduleRepository careScheduleRepository;
    @Autowired private UserService userService;

    @MockitoBean private RateLimitedTelegramSender telegramSender;

    @BeforeEach
    void resetSenderMock() {
        Mockito.reset(telegramSender);
    }

    @AfterEach
    void cleanup() {
        careScheduleRepository.deleteAll();
        plantRepository.deleteAll();
        locationRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Tomorrow-back push идёт юзеру, у кого до конца отпуска ~23.5 часа")
    void should_send_tomorrow_back_push_when_vacation_ends_in_24h() {
        User user = saveUserWithVacation(1000L, NOW_LDT.plusHours(23).plusMinutes(30));

        scheduler.tick();

        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramSender, times(1)).enqueue(cap.capture());

        SendMessage sent = cap.getValue();
        assertThat(sent.getChatId()).isEqualTo(user.getTelegramChatId().toString());
        assertThat(sent.getText()).contains("🏖");
        assertThat(sent.getText()).containsIgnoringCase("завтра");

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sent.getReplyMarkup();
        List<String> callbacks = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();

        assertThat(callbacks)
                .contains("MENU:VACATION_EXTEND_3", "MENU:VACATION_EXTEND_7");
    }

    @Test
    @DisplayName("Нет tomorrow-back, если до конца отпуска ещё > 24 часов")
    void should_not_send_tomorrow_back_when_outside_window() {
        saveUserWithVacation(1001L, NOW_LDT.plusHours(30));

        scheduler.tick();

        verify(telegramSender, never()).enqueue(any(SendMessage.class));
    }

    @Test
    @DisplayName("Граница окна: pausedUntil ровно в now+24h НЕ попадает (right-exclusive)")
    void should_not_send_tomorrow_back_when_paused_until_equals_upper_bound() {
        // pausedUntil == now + 24h. Окно [now+23h; now+24h) — правая граница исключена.
        saveUserWithVacation(1002L, NOW_LDT.plusHours(24));

        scheduler.tick();

        verify(telegramSender, never()).enqueue(any(SendMessage.class));
    }

    @Test
    @DisplayName("Граница окна: pausedUntil ровно в now+23h попадает (left-inclusive)")
    void should_send_tomorrow_back_when_paused_until_equals_lower_bound() {
        // pausedUntil == now + 23h. Окно [now+23h; now+24h) — левая граница включена.
        saveUserWithVacation(1011L, NOW_LDT.plusHours(23));

        scheduler.tick();

        verify(telegramSender, times(1)).enqueue(any(SendMessage.class));
    }

    @Test
    @DisplayName("Welcome-back: отпуск закончился → push + paused_until=null")
    void should_send_welcome_back_and_clear_paused_until_when_vacation_ended() {
        User user = saveUserWithVacation(1003L, NOW_LDT.minusHours(1));

        scheduler.tick();

        verify(telegramSender, times(1)).enqueue(any(SendMessage.class));

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getPausedUntil())
                .as("paused_until должен быть очищен после welcome-back")
                .isNull();
    }

    @Test
    @DisplayName("Welcome-back с просрочкой содержит список «За время отпуска накопилось» + растение")
    void should_include_overdue_tasks_in_welcome_back_message() {
        User user = saveUserWithVacation(1004L, NOW_LDT.minusHours(1));
        Plant plant = savePlant(user, "Монстера");
        saveSchedule(plant, TaskType.WATERING, NOW_LDT.minusDays(5));

        scheduler.tick();

        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramSender).enqueue(cap.capture());

        String text = cap.getValue().getText();
        assertThat(text).contains("С возвращением");
        assertThat(text).contains("За время отпуска накопилось");
        assertThat(text).contains("Монстера");
        assertThat(text).contains("полить");
        assertThat(text).contains("просрочено 5");
    }

    @Test
    @DisplayName("Welcome-back НЕ сдвигает next_due_at у просроченных расписаний")
    void should_not_shift_next_due_at_after_welcome_back() {
        User user = saveUserWithVacation(1005L, NOW_LDT.minusHours(1));
        Plant plant = savePlant(user, "Фикус");
        LocalDateTime originalDueAt = NOW_LDT.minusDays(5).truncatedTo(ChronoUnit.MICROS);
        CareSchedule schedule = saveSchedule(plant, TaskType.WATERING, originalDueAt);

        scheduler.tick();

        CareSchedule reloaded = careScheduleRepository.findById(schedule.getId()).orElseThrow();
        assertThat(reloaded.getNextDueAt())
                .as("welcome-back только показывает overdue, не сбрасывает next_due_at")
                .isEqualTo(originalDueAt);
    }

    @Test
    @DisplayName("Welcome-back без просрочки — короткое сообщение без блока «накопилось»")
    void should_send_clean_welcome_back_when_no_overdue() {
        saveUserWithVacation(1006L, NOW_LDT.minusHours(1));

        scheduler.tick();

        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramSender).enqueue(cap.capture());

        String text = cap.getValue().getText();
        assertThat(text).contains("С возвращением");
        assertThat(text).doesNotContain("За время отпуска накопилось");
    }

    @Test
    @DisplayName("Welcome-back idempotent: второй тик после очистки paused_until не шлёт повтор")
    void should_send_welcome_back_only_once_when_tick_runs_twice() {
        saveUserWithVacation(1007L, NOW_LDT.minusHours(1));

        scheduler.tick();
        scheduler.tick();

        // После первого тика paused_until = null, во втором тике findVacationEnded
        // его не подберёт. Итого ровно один enqueue.
        verify(telegramSender, times(1)).enqueue(any(SendMessage.class));
    }

    @Test
    @DisplayName("Заблокированному юзеру tomorrow-back не уходит")
    void should_skip_blocked_users_in_tomorrow_back() {
        User user = saveUserWithVacation(1008L, NOW_LDT.plusHours(23).plusMinutes(30));
        user.setBlocked(true);
        userRepository.save(user);

        scheduler.tick();

        verify(telegramSender, never()).enqueue(any(SendMessage.class));
    }

    @Test
    @DisplayName("Заблокированному юзеру welcome-back не уходит")
    void should_skip_blocked_users_in_welcome_back() {
        User user = saveUserWithVacation(1009L, NOW_LDT.minusHours(1));
        user.setBlocked(true);
        userRepository.save(user);

        scheduler.tick();

        verify(telegramSender, never()).enqueue(any(SendMessage.class));
        // paused_until НЕ должен очищаться для заблокированных — иначе при разблокировке
        // юзер пропустит welcome-back и попадёт сразу в обычный поток уведомлений.
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getPausedUntil()).isNotNull();
    }

    @Test
    @DisplayName("Расчёт paused_until для Asia/Almaty (non-UTC) — кросс-проверка с UserService")
    void should_compute_vacation_end_correctly_for_almaty_timezone() {
        User user = newUser(1010L, "Asia/Almaty");

        // startVacation использует тот же Clock → результат детерминирован.
        User updated = userService.startVacation(user, 7);

        // Сейчас в Almaty (UTC+5/+6) = 2026-05-23 + 5-6h. +7 дней → 2026-05-30 в Almaty.
        // Назад в UTC получим то же time-of-day, что и FIXED_NOW (10:00:00).
        LocalDateTime expectedUtc = java.time.ZonedDateTime.ofInstant(FIXED_NOW, java.time.ZoneId.of("Asia/Almaty"))
                .plusDays(7)
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();

        assertThat(updated.getPausedUntil()).isEqualTo(expectedUtc);
        // Время в UTC должно совпадать с FIXED_NOW по часам/минутам (TZ-сдвиг сохраняется).
        assertThat(updated.getPausedUntil().toLocalTime()).isEqualTo(NOW_LDT.toLocalTime());
    }

    // ===== helpers =====

    private User newUser(long chatId, String timezone) {
        User u = User.builder()
                .telegramChatId(chatId)
                .timezone(timezone)
                .blocked(false)
                .build();
        return userRepository.save(u);
    }

    private User saveUserWithVacation(long chatId, LocalDateTime pausedUntil) {
        User u = newUser(chatId, "UTC");
        u.setPausedUntil(pausedUntil.truncatedTo(ChronoUnit.MICROS));
        return userRepository.save(u);
    }

    private Location saveDefaultLocation(User user) {
        return locationRepository.findByUserIdAndDefaultLocationTrue(user.getId())
                .orElseGet(() -> locationRepository.save(Location.builder()
                        .user(user)
                        .name(Location.DEFAULT_NAME)
                        .emoji(Location.DEFAULT_EMOJI)
                        .defaultLocation(true)
                        .build()));
    }

    private Plant savePlant(User user, String name) {
        Location location = saveDefaultLocation(user);
        return plantRepository.save(Plant.builder()
                .user(user)
                .location(location)
                .name(name)
                .build());
    }

    private CareSchedule saveSchedule(Plant plant, TaskType type, LocalDateTime nextDueAt) {
        return careScheduleRepository.save(CareSchedule.builder()
                .plant(plant)
                .taskType(type)
                .intervalDays(7)
                .nextDueAt(nextDueAt.truncatedTo(ChronoUnit.MICROS))
                .active(true)
                .build());
    }

    /**
     * Подменяет:
     * - Clock — на фиксированный, чтобы расчёты «сейчас + N часов» были детерминированы;
     * - TelegramClientProvider — на стаб, возвращающий мок TelegramClient. Нужен ещё на
     *   ApplicationReadyEvent (BotCommandsRegistrar дёргает execute() до тестов), хотя
     *   сам шедулер отправляет через RateLimitedTelegramSender (замокан отдельно).
     */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        public Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        public TelegramClient testTelegramClient() {
            return Mockito.mock(TelegramClient.class);
        }

        @Bean
        @Primary
        public TelegramClientProvider testTelegramClientProvider(TelegramClient testTelegramClient) {
            return () -> testTelegramClient;
        }
    }
}

package com.plantcare.bot.service;

import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.PhotoProgressFrequency;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.service.PhotoProgressService;
import com.plantcare.bot.telegram.RateLimitedTelegramSender;
import com.plantcare.bot.telegram.SendCallbacks;
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

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Дополнительные branch-тесты {@link PhotoProgressSchedulerService} за пределами
 * {@link PhotoProgressSchedulerServiceTest} — пустой список, {@code user==null},
 * quiet-hours, устойчивость к исключению на одном растении и экранирование
 * Markdown в имени растения.
 *
 * <p>ВАЖНО (см. отчёт агента): как и {@link AcclimationSchedulerService}, этот
 * класс не инжектирует {@link java.time.Clock} — {@code isQuietHours} читает
 * {@code Instant.now()} напрямую (нарушение TIME RULE CLAUDE.md, существующий
 * production-код, не трогаем). Quiet-hours поэтому проверяется почти-суточным
 * окном (00:00-23:59), детерминированным с точностью до секунды в сутки —
 * тот же приём, что и в {@code AcclimationSchedulerServiceTest}.
 */
@DisplayName("PhotoProgressSchedulerService — branch-покрытие")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PhotoProgressSchedulerServiceBranchTest {

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private PhotoProgressService photoProgressService;

    @Mock
    private RateLimitedTelegramSender telegramSender;

    @InjectMocks
    private PhotoProgressSchedulerService service;

    private static User activeUser(Long chatId) {
        return activeUser(chatId, "Europe/Minsk", LocalTime.of(0, 0), LocalTime.of(0, 0));
    }

    private static User activeUser(Long chatId, String tz, LocalTime start, LocalTime end) {
        User user = User.builder()
                .telegramChatId(chatId)
                .timezone(tz)
                .quietHoursStart(start)
                .quietHoursEnd(end)
                .blocked(false)
                .build();
        ReflectionTestUtils.setField(user, "id", chatId);
        return user;
    }

    private static Plant duePlant(Long id, String name, User user) {
        Plant plant = Plant.builder()
                .user(user)
                .name(name)
                .photoProgressFrequency(PhotoProgressFrequency.P2W)
                .build();
        plant.setNextPhotoDueAt(LocalDateTime.now().minusMinutes(5));
        ReflectionTestUtils.setField(plant, "id", id);
        return plant;
    }

    @Test
    @DisplayName("Пустой список кандидатов → тик не трогает Telegram")
    void should_do_nothing_when_no_plants_due() {
        when(plantRepository.findPhotoProgressDue(any(), any())).thenReturn(List.of());

        service.checkPhotoPrompts();

        verifyNoInteractions(telegramSender, photoProgressService);
    }

    @Test
    @DisplayName("plant.getUser() == null → пропускается без падения тика")
    void should_skip_plant_when_user_is_null() {
        Plant orphan = duePlant(10L, "Ничейное", null);
        when(plantRepository.findPhotoProgressDue(any(), any())).thenReturn(List.of(orphan));

        assertThatCode(() -> service.checkPhotoPrompts()).doesNotThrowAnyException();

        verifyNoInteractions(telegramSender);
    }

    @Test
    @DisplayName("Тихие часы активны (00:00-23:59) → prompt не отправляется")
    void should_skip_when_quiet_hours_active() {
        User user = activeUser(100L, "Europe/Minsk", LocalTime.of(0, 0), LocalTime.of(23, 59));
        Plant plant = duePlant(10L, "Монстера", user);
        when(plantRepository.findPhotoProgressDue(any(), any())).thenReturn(List.of(plant));

        service.checkPhotoPrompts();

        verifyNoInteractions(telegramSender);
    }

    @Test
    @DisplayName("Невалидная TZ юзера падает в UTC-фолбэк, тик не падает")
    void should_fallback_to_utc_and_not_crash_when_timezone_invalid() {
        User user = activeUser(100L, "Not/AZone", LocalTime.of(0, 0), LocalTime.of(0, 0));
        Plant plant = duePlant(10L, "Монстера", user);
        when(plantRepository.findPhotoProgressDue(any(), any())).thenReturn(List.of(plant));

        assertThatCode(() -> service.checkPhotoPrompts()).doesNotThrowAnyException();

        // quiet-hours отключены (start==end) → prompt всё равно уходит несмотря на TZ-фолбэк.
        verify(telegramSender).enqueue(any(SendMessage.class), any(SendCallbacks.class));
    }

    @Test
    @DisplayName("Ошибка на одном растении не прерывает обработку остальных")
    void should_continue_when_one_plant_throws() {
        User brokenOwner = activeUser(100L);
        Plant broken = duePlant(10L, "Сломанное", brokenOwner);
        // enqueue бросает только для первого plant id — второй проходит штатно.
        User healthyOwner = activeUser(200L);
        Plant healthy = duePlant(20L, "Здоровое", healthyOwner);

        when(plantRepository.findPhotoProgressDue(any(), any())).thenReturn(List.of(broken, healthy));
        org.mockito.Mockito.doThrow(new RuntimeException("telegram exploded"))
                .doNothing()
                .when(telegramSender).enqueue(any(SendMessage.class), any(SendCallbacks.class));

        service.checkPhotoPrompts();

        verify(telegramSender, times(2)).enqueue(any(SendMessage.class), any(SendCallbacks.class));
    }

    @Nested
    @DisplayName("Экранирование Markdown в имени растения")
    class MarkdownEscaping {

        @Test
        @DisplayName("Спецсимволы Markdown в имени растения экранируются в тексте prompt'а")
        void should_escape_markdown_special_characters_in_plant_name() {
            User user = activeUser(100L);
            Plant plant = duePlant(10L, "Ficus_star*[rare]", user);
            when(plantRepository.findPhotoProgressDue(any(), any())).thenReturn(List.of(plant));

            service.checkPhotoPrompts();

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender).enqueue(captor.capture(), any(SendCallbacks.class));
            assertThat(captor.getValue().getText()).contains("Ficus\\_star\\*\\[rare\\]");
        }
    }
}

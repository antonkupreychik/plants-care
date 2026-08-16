package com.plantcare.core.service;

import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для UserSettingsService")
class UserSettingsServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CareScheduleRepository careScheduleRepository;

    @Mock
    private QuietHoursPolicy quietHoursPolicy;

    @InjectMocks
    private UserSettingsService service;

    private User user;

    @BeforeEach
    void setUpUser() {
        user = User.builder()
                .telegramChatId(42L)
                .timezone("Europe/Moscow")
                .quietHoursStart(LocalTime.of(22, 0))
                .quietHoursEnd(LocalTime.of(9, 0))
                .build();
        ReflectionTestUtils.setField(user, "id", 1L);

        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(careScheduleRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(quietHoursPolicy.isQuiet(any(), any())).thenReturn(false);
    }

    // ===== changeTimezone =====

    @Nested
    @DisplayName("changeTimezone")
    class ChangeTimezoneTests {

        @Test
        @DisplayName("should_return_zero_rescheduled_and_no_conflict_when_schedule_list_is_empty")
        void should_return_zero_rescheduled_and_no_conflict_when_schedule_list_is_empty() {
            when(careScheduleRepository.findActiveSchedulesByUserId(1L)).thenReturn(List.of());

            var result = service.changeTimezone(user, ZoneId.of("Asia/Almaty"));

            assertThat(result.rescheduledCount()).isZero();
            assertThat(result.quietHoursConflict()).isFalse();
            assertThat(result.newZoneId()).isEqualTo("Asia/Almaty");
        }

        @Test
        @DisplayName("should_skip_schedule_without_NPE_when_nextDueAt_is_null")
        void should_skip_schedule_without_NPE_when_nextDueAt_is_null() {
            CareSchedule nullNext = CareSchedule.builder()
                    .nextDueAt(null)
                    .active(true)
                    .build();
            when(careScheduleRepository.findActiveSchedulesByUserId(1L)).thenReturn(List.of(nullNext));

            assertThatNoException().isThrownBy(() ->
                    service.changeTimezone(user, ZoneId.of("Asia/Almaty")));
            assertThat(nullNext.getNextDueAt()).isNull();
        }

        @Test
        @DisplayName("should_preserve_local_wall_clock_time_when_timezone_changes_EuropeMoscow_to_AsiaAlmaty")
        void should_preserve_local_wall_clock_time_when_timezone_changes_EuropeMoscow_to_AsiaAlmaty() {
            // arrange
            // nextDueAt stored as UTC wall-clock: 06:00 UTC = 09:00 Moscow (UTC+3)
            // After change to Almaty (UTC+5): local time must remain 09:00
            // → new UTC = 09:00 Almaty = 04:00 UTC
            LocalDateTime nextDueAtUtc = LocalDateTime.of(2026, 8, 16, 6, 0, 0);
            CareSchedule schedule = buildScheduleWithNextDueAt(nextDueAtUtc);
            when(careScheduleRepository.findActiveSchedulesByUserId(1L)).thenReturn(List.of(schedule));

            // act
            service.changeTimezone(user, ZoneId.of("Asia/Almaty"));

            // assert: local wall clock stays 09:00, UTC shifts from 06:00 to 04:00
            LocalDateTime newUtc = schedule.getNextDueAt();
            assertThat(newUtc.getHour()).as("new UTC hour").isEqualTo(4);
            assertThat(newUtc.getMinute()).as("new UTC minute").isZero();
        }

        @Test
        @DisplayName("should_leave_UTC_stored_value_unchanged_when_old_and_new_zones_have_same_offset_EuropeMoscow_to_EuropeMinsk")
        void should_leave_UTC_stored_value_unchanged_when_old_and_new_zones_have_same_offset_EuropeMoscow_to_EuropeMinsk() {
            // arrange
            // Moscow and Minsk are both UTC+3 (Moscow permanently, Minsk has no DST).
            // nextDueAt = 06:00 UTC = 09:00 Moscow local → 09:00 Minsk local → still 06:00 UTC.
            LocalDateTime nextDueAtUtc = LocalDateTime.of(2026, 8, 16, 6, 0, 0);
            CareSchedule schedule = buildScheduleWithNextDueAt(nextDueAtUtc);
            when(careScheduleRepository.findActiveSchedulesByUserId(1L)).thenReturn(List.of(schedule));

            // act
            service.changeTimezone(user, ZoneId.of("Europe/Minsk"));

            // assert: UTC value is unchanged (local time preserved, offsets equal)
            LocalDateTime newUtc = schedule.getNextDueAt();
            assertThat(newUtc).isEqualTo(nextDueAtUtc);
        }

        @Test
        @DisplayName("should_detect_quiet_hours_conflict_when_rescheduled_instant_falls_in_quiet_window")
        void should_detect_quiet_hours_conflict_when_rescheduled_instant_falls_in_quiet_window() {
            LocalDateTime nextDueAtUtc = LocalDateTime.of(2026, 8, 16, 6, 0, 0);
            CareSchedule schedule = buildScheduleWithNextDueAt(nextDueAtUtc);
            when(careScheduleRepository.findActiveSchedulesByUserId(1L)).thenReturn(List.of(schedule));
            when(quietHoursPolicy.isQuiet(any(), any())).thenReturn(true);

            var result = service.changeTimezone(user, ZoneId.of("Asia/Almaty"));

            assertThat(result.quietHoursConflict()).isTrue();
        }

        @Test
        @DisplayName("should_not_set_quiet_hours_conflict_when_policy_returns_false_for_all_schedules")
        void should_not_set_quiet_hours_conflict_when_policy_returns_false_for_all_schedules() {
            LocalDateTime nextDueAtUtc = LocalDateTime.of(2026, 8, 16, 14, 0, 0);
            CareSchedule schedule = buildScheduleWithNextDueAt(nextDueAtUtc);
            when(careScheduleRepository.findActiveSchedulesByUserId(1L)).thenReturn(List.of(schedule));
            when(quietHoursPolicy.isQuiet(any(), any())).thenReturn(false);

            var result = service.changeTimezone(user, ZoneId.of("Asia/Almaty"));

            assertThat(result.quietHoursConflict()).isFalse();
        }

        @Test
        @DisplayName("should_count_schedules_correctly_when_multiple_active_schedules_rescheduled")
        void should_count_schedules_correctly_when_multiple_active_schedules_rescheduled() {
            LocalDateTime ts = LocalDateTime.of(2026, 8, 16, 6, 0, 0);
            CareSchedule s1 = buildScheduleWithNextDueAt(ts);
            CareSchedule s2 = buildScheduleWithNextDueAt(ts.plusDays(1));
            CareSchedule s3 = buildScheduleWithNextDueAt(ts.plusDays(2));
            when(careScheduleRepository.findActiveSchedulesByUserId(1L)).thenReturn(List.of(s1, s2, s3));

            var result = service.changeTimezone(user, ZoneId.of("Asia/Almaty"));

            assertThat(result.rescheduledCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("should_update_user_timezone_field_when_changeTimezone_is_called")
        void should_update_user_timezone_field_when_changeTimezone_is_called() {
            when(careScheduleRepository.findActiveSchedulesByUserId(anyLong())).thenReturn(List.of());

            service.changeTimezone(user, ZoneId.of("Asia/Almaty"));

            assertThat(user.getTimezone()).isEqualTo("Asia/Almaty");
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("should_save_rescheduled_schedules_when_schedules_exist")
        void should_save_rescheduled_schedules_when_schedules_exist() {
            CareSchedule schedule = buildScheduleWithNextDueAt(LocalDateTime.of(2026, 8, 16, 6, 0, 0));
            when(careScheduleRepository.findActiveSchedulesByUserId(1L)).thenReturn(List.of(schedule));

            service.changeTimezone(user, ZoneId.of("Asia/Almaty"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<CareSchedule>> captor = ArgumentCaptor.forClass(List.class);
            verify(careScheduleRepository).saveAll(captor.capture());
            assertThat(captor.getValue()).containsExactly(schedule);
        }

        @Test
        @DisplayName("should_fallback_to_UTC_for_old_zone_when_user_timezone_is_blank")
        void should_fallback_to_UTC_for_old_zone_when_user_timezone_is_blank() {
            user.setTimezone("");
            // nextDueAt as UTC wall-clock 09:00 UTC. blank = UTC, so local time = 09:00.
            // new zone = Asia/Almaty UTC+5 → new UTC = 04:00
            LocalDateTime nextDueAtUtc = LocalDateTime.of(2026, 8, 16, 9, 0, 0);
            CareSchedule schedule = buildScheduleWithNextDueAt(nextDueAtUtc);
            when(careScheduleRepository.findActiveSchedulesByUserId(1L)).thenReturn(List.of(schedule));

            assertThatNoException().isThrownBy(() ->
                    service.changeTimezone(user, ZoneId.of("Asia/Almaty")));
            // Local time 09:00 stays 09:00 in Almaty = 04:00 UTC
            assertThat(schedule.getNextDueAt().getHour()).isEqualTo(4);
        }

        @Test
        @DisplayName("should_only_flag_conflict_when_at_least_one_schedule_is_in_quiet_hours_AsiaAlmaty")
        void should_only_flag_conflict_when_at_least_one_schedule_is_in_quiet_hours_AsiaAlmaty() {
            CareSchedule safe = buildScheduleWithNextDueAt(LocalDateTime.of(2026, 8, 16, 6, 0, 0));
            CareSchedule conflicting = buildScheduleWithNextDueAt(LocalDateTime.of(2026, 8, 16, 8, 0, 0));
            when(careScheduleRepository.findActiveSchedulesByUserId(1L)).thenReturn(List.of(safe, conflicting));
            // first call → not quiet, second call → quiet
            when(quietHoursPolicy.isQuiet(any(), any())).thenReturn(false, true);

            var result = service.changeTimezone(user, ZoneId.of("Asia/Almaty"));

            assertThat(result.quietHoursConflict()).isTrue();
        }
    }

    // ===== setQuietHoursStart =====

    @Nested
    @DisplayName("setQuietHoursStart")
    class SetQuietHoursStartTests {

        @Test
        @DisplayName("should_update_quiet_hours_start_and_save_user_when_valid_time_provided")
        void should_update_quiet_hours_start_and_save_user_when_valid_time_provided() {
            LocalTime newStart = LocalTime.of(23, 30);

            service.setQuietHoursStart(user, newStart);

            assertThat(user.getQuietHoursStart()).isEqualTo(newStart);
            verify(userRepository).save(user);
        }
    }

    // ===== setQuietHoursEnd =====

    @Nested
    @DisplayName("setQuietHoursEnd")
    class SetQuietHoursEndTests {

        @Test
        @DisplayName("should_update_quiet_hours_end_and_save_user_when_valid_time_provided")
        void should_update_quiet_hours_end_and_save_user_when_valid_time_provided() {
            LocalTime newEnd = LocalTime.of(8, 0);

            service.setQuietHoursEnd(user, newEnd);

            assertThat(user.getQuietHoursEnd()).isEqualTo(newEnd);
            verify(userRepository).save(user);
        }
    }

    // ===== resetQuietHours =====

    @Nested
    @DisplayName("resetQuietHours")
    class ResetQuietHoursTests {

        @Test
        @DisplayName("should_reset_quiet_hours_to_defaults_22_to_9_when_reset_called")
        void should_reset_quiet_hours_to_defaults_22_to_9_when_reset_called() {
            user.setQuietHoursStart(LocalTime.of(0, 0));
            user.setQuietHoursEnd(LocalTime.of(0, 0));

            service.resetQuietHours(user);

            assertThat(user.getQuietHoursStart()).isEqualTo(LocalTime.of(22, 0));
            assertThat(user.getQuietHoursEnd()).isEqualTo(LocalTime.of(9, 0));
            verify(userRepository).save(user);
        }
    }

    // ===== buildTimezoneConfirmation =====

    @Nested
    @DisplayName("buildTimezoneConfirmation")
    class BuildTimezoneConfirmationTests {

        @Test
        @DisplayName("should_include_rescheduled_text_when_rescheduled_count_is_positive")
        void should_include_rescheduled_text_when_rescheduled_count_is_positive() {
            var result = new UserSettingsService.TimezoneChangeResult("Asia/Almaty", 3, false);

            String text = UserSettingsService.buildTimezoneConfirmation(result, null);

            assertThat(text).contains("Расписания пересчитаны").contains("Asia/Almaty");
        }

        @Test
        @DisplayName("should_include_updated_text_when_no_schedules_rescheduled")
        void should_include_updated_text_when_no_schedules_rescheduled() {
            var result = new UserSettingsService.TimezoneChangeResult("Europe/Minsk", 0, false);

            String text = UserSettingsService.buildTimezoneConfirmation(result, null);

            assertThat(text).contains("Часовой пояс обновлён").contains("Europe/Minsk");
        }

        @Test
        @DisplayName("should_append_quiet_hours_warning_with_end_time_when_conflict_and_quietHoursEnd_not_null")
        void should_append_quiet_hours_warning_with_end_time_when_conflict_and_quietHoursEnd_not_null() {
            var result = new UserSettingsService.TimezoneChangeResult("Asia/Almaty", 2, true);
            LocalTime quietEnd = LocalTime.of(9, 0);

            String text = UserSettingsService.buildTimezoneConfirmation(result, quietEnd);

            assertThat(text).contains("тихие часы").contains("09:00");
        }

        @Test
        @DisplayName("should_not_append_quiet_hours_warning_when_conflict_true_but_quietHoursEnd_is_null")
        void should_not_append_quiet_hours_warning_when_conflict_true_but_quietHoursEnd_is_null() {
            var result = new UserSettingsService.TimezoneChangeResult("Asia/Almaty", 2, true);

            String text = UserSettingsService.buildTimezoneConfirmation(result, null);

            assertThat(text).doesNotContain("тихие часы");
        }

        @Test
        @DisplayName("should_not_append_quiet_hours_warning_when_no_conflict_even_if_quietHoursEnd_provided")
        void should_not_append_quiet_hours_warning_when_no_conflict_even_if_quietHoursEnd_provided() {
            var result = new UserSettingsService.TimezoneChangeResult("Asia/Almaty", 2, false);

            String text = UserSettingsService.buildTimezoneConfirmation(result, LocalTime.of(9, 0));

            assertThat(text).doesNotContain("тихие часы");
        }

        @Test
        @DisplayName("should_format_quiet_hours_end_as_HH_mm_when_conflict_and_end_provided")
        void should_format_quiet_hours_end_as_HH_mm_when_conflict_and_end_provided() {
            var result = new UserSettingsService.TimezoneChangeResult("Europe/Moscow", 1, true);

            String text = UserSettingsService.buildTimezoneConfirmation(result, LocalTime.of(9, 30));

            assertThat(text).contains("09:30");
        }
    }

    // ===== helpers =====

    private CareSchedule buildScheduleWithNextDueAt(LocalDateTime nextDueAt) {
        CareSchedule s = CareSchedule.builder()
                .active(true)
                .nextDueAt(nextDueAt)
                .intervalDays(7)
                .build();
        return s;
    }
}

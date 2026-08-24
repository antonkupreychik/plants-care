package com.plantcare.core.service;

import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.PlantAnniversarySentRepository;
import com.plantcare.core.repository.PlantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Чистый Mockito unit-тест {@link PlantAnniversaryService}, дополняющий
 * Testcontainers-based {@link PlantAnniversaryServiceTest}. Здесь покрываются
 * ветки, которые дороже/неудобно гонять через реальный Postgres:
 * високосный день рождения (29 февраля), fallback невалидной/пустой таймзоны,
 * склонение чисел (год/года/лет, раз/раза), пустой список кандидатов.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlantAnniversaryService — unit-покрытие веток (issue #117)")
class PlantAnniversaryServiceUnitTest {

    @Mock private PlantRepository plantRepository;
    @Mock private PlantAnniversarySentRepository sentRepository;
    @Mock private CareHistoryRepository careHistoryRepository;

    @InjectMocks
    private PlantAnniversaryService service;

    private User userWithZone(Long id, String timezone) {
        User user = User.builder().telegramChatId(id).timezone(timezone).blocked(false).build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Plant plantOf(User user, Long id, LocalDate acquiredAt) {
        Plant plant = Plant.builder().user(user).name("Растение").acquiredAt(acquiredAt).build();
        ReflectionTestUtils.setField(plant, "id", id);
        return plant;
    }

    @Nested
    @DisplayName("findDueAnniversaries — базовые ветки")
    class FindDueAnniversariesTests {

        @Test
        @DisplayName("should_return_empty_list_when_no_candidates_in_repository")
        void should_return_empty_list_when_no_candidates_in_repository() {
            when(plantRepository.findActiveWithAcquiredDate()).thenReturn(List.of());

            List<PlantAnniversaryService.Anniversary> result =
                    service.findDueAnniversaries(Instant.parse("2026-05-24T07:00:00Z"));

            assertThat(result).isEmpty();
            verify(sentRepository, never()).existsByPlantIdAndAnniversaryYear(anyLong(), anyInt());
        }

        @Test
        @DisplayName("should_skip_plant_when_user_reference_is_null")
        void should_skip_plant_when_user_reference_is_null() {
            Plant orphan = Plant.builder().name("Сирота").acquiredAt(LocalDate.of(2025, 5, 24)).build();
            ReflectionTestUtils.setField(orphan, "id", 1L);
            when(plantRepository.findActiveWithAcquiredDate()).thenReturn(List.of(orphan));

            List<PlantAnniversaryService.Anniversary> result =
                    service.findDueAnniversaries(Instant.parse("2026-05-24T07:00:00Z"));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should_treat_blank_timezone_as_utc")
        void should_treat_blank_timezone_as_utc() {
            User user = userWithZone(1L, "   ");
            Plant plant = plantOf(user, 10L, LocalDate.of(2025, 5, 24));
            when(plantRepository.findActiveWithAcquiredDate()).thenReturn(List.of(plant));
            when(sentRepository.existsByPlantIdAndAnniversaryYear(10L, 2026)).thenReturn(false);
            when(careHistoryRepository.countActiveByPlantIdAndTaskType(10L, TaskType.WATERING)).thenReturn(2L);

            List<PlantAnniversaryService.Anniversary> result =
                    service.findDueAnniversaries(Instant.parse("2026-05-24T07:00:00Z"));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).yearsOld()).isEqualTo(1);
        }

        @Test
        @DisplayName("should_treat_invalid_timezone_as_utc")
        void should_treat_invalid_timezone_as_utc() {
            User user = userWithZone(1L, "Not/A/Real/Zone");
            Plant plant = plantOf(user, 11L, LocalDate.of(2025, 5, 24));
            when(plantRepository.findActiveWithAcquiredDate()).thenReturn(List.of(plant));
            when(sentRepository.existsByPlantIdAndAnniversaryYear(11L, 2026)).thenReturn(false);
            when(careHistoryRepository.countActiveByPlantIdAndTaskType(11L, TaskType.WATERING)).thenReturn(0L);

            List<PlantAnniversaryService.Anniversary> result =
                    service.findDueAnniversaries(Instant.parse("2026-05-24T07:00:00Z"));

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("should_skip_when_already_marked_sent_for_this_year")
        void should_skip_when_already_marked_sent_for_this_year() {
            User user = userWithZone(1L, "Europe/Moscow");
            Plant plant = plantOf(user, 12L, LocalDate.of(2024, 5, 24));
            when(plantRepository.findActiveWithAcquiredDate()).thenReturn(List.of(plant));
            when(sentRepository.existsByPlantIdAndAnniversaryYear(12L, 2026)).thenReturn(true);

            List<PlantAnniversaryService.Anniversary> result = service.findDueAnniversaries(
                    LocalDate.of(2026, 5, 24).atStartOfDay(ZoneId.of("Europe/Moscow")).toInstant());

            assertThat(result).isEmpty();
            verify(careHistoryRepository, never()).countActiveByPlantIdAndTaskType(anyLong(), any());
        }

        @Test
        @DisplayName("should_process_multiple_candidates_independently")
        void should_process_multiple_candidates_independently() {
            User user = userWithZone(1L, "Europe/Moscow");
            Plant due = plantOf(user, 20L, LocalDate.of(2025, 5, 24));
            Plant notDue = plantOf(user, 21L, LocalDate.of(2025, 6, 1));
            when(plantRepository.findActiveWithAcquiredDate()).thenReturn(List.of(due, notDue));
            when(sentRepository.existsByPlantIdAndAnniversaryYear(20L, 2026)).thenReturn(false);
            when(careHistoryRepository.countActiveByPlantIdAndTaskType(20L, TaskType.WATERING)).thenReturn(3L);

            List<PlantAnniversaryService.Anniversary> result = service.findDueAnniversaries(
                    LocalDate.of(2026, 5, 24).atStartOfDay(ZoneId.of("Europe/Moscow")).toInstant());

            assertThat(result).extracting(PlantAnniversaryService.Anniversary::plantId).containsExactly(20L);
        }
    }

    @Nested
    @DisplayName("isAnniversaryDay — календарные ветки, включая 29 февраля")
    class IsAnniversaryDayTests {

        @Test
        @DisplayName("should_match_when_month_and_day_equal")
        void should_match_when_month_and_day_equal() {
            assertThat(PlantAnniversaryService.isAnniversaryDay(
                    LocalDate.of(2024, 5, 24), LocalDate.of(2026, 5, 24))).isTrue();
        }

        @Test
        @DisplayName("should_not_match_when_month_differs")
        void should_not_match_when_month_differs() {
            assertThat(PlantAnniversaryService.isAnniversaryDay(
                    LocalDate.of(2024, 5, 24), LocalDate.of(2026, 6, 24))).isFalse();
        }

        @Test
        @DisplayName("should_not_match_when_day_differs")
        void should_not_match_when_day_differs() {
            assertThat(PlantAnniversaryService.isAnniversaryDay(
                    LocalDate.of(2024, 5, 24), LocalDate.of(2026, 5, 25))).isFalse();
        }

        @Test
        @DisplayName("should_map_feb29_acquired_to_feb28_when_today_is_non_leap_year")
        void should_map_feb29_acquired_to_feb28_when_today_is_non_leap_year() {
            // Растение заведено 29 февраля високосного 2024. 2026 — не високосный,
            // поэтому годовщина смещается на 28 февраля (см. javadoc метода).
            assertThat(PlantAnniversaryService.isAnniversaryDay(
                    LocalDate.of(2024, 2, 29), LocalDate.of(2026, 2, 28))).isTrue();
        }

        @Test
        @DisplayName("should_not_map_feb29_to_feb28_when_today_is_leap_year")
        void should_not_map_feb29_to_feb28_when_today_is_leap_year() {
            // 2028 — високосный, значит 28 февраля 2028 НЕ годовщина (29-е ещё не наступило).
            assertThat(PlantAnniversaryService.isAnniversaryDay(
                    LocalDate.of(2024, 2, 29), LocalDate.of(2028, 2, 28))).isFalse();
        }

        @Test
        @DisplayName("should_match_feb29_acquired_on_feb29_of_leap_year")
        void should_match_feb29_acquired_on_feb29_of_leap_year() {
            assertThat(PlantAnniversaryService.isAnniversaryDay(
                    LocalDate.of(2024, 2, 29), LocalDate.of(2028, 2, 29))).isTrue();
        }

        @Test
        @DisplayName("should_not_treat_feb28_acquired_as_leap_day_special_case")
        void should_not_treat_feb28_acquired_as_leap_day_special_case() {
            // acquired = 28 февраля (не 29-е) — не должно триггерить leap-day ветку,
            // обычное совпадение месяца/дня решает вопрос.
            assertThat(PlantAnniversaryService.isAnniversaryDay(
                    LocalDate.of(2023, 2, 28), LocalDate.of(2026, 2, 28))).isTrue();
            assertThat(PlantAnniversaryService.isAnniversaryDay(
                    LocalDate.of(2023, 2, 28), LocalDate.of(2026, 3, 1))).isFalse();
        }
    }

    @Nested
    @DisplayName("markSent")
    class MarkSentTests {

        @Test
        @DisplayName("should_save_marker_when_no_conflict")
        void should_save_marker_when_no_conflict() {
            service.markSent(5L, 2026, Instant.parse("2026-05-24T07:00:00Z"));

            verify(sentRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("should_swallow_data_integrity_violation_on_race")
        void should_swallow_data_integrity_violation_on_race() {
            when(sentRepository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

            // не должно бросать наружу
            service.markSent(5L, 2026, Instant.parse("2026-05-24T07:00:00Z"));

            verify(sentRepository, times(1)).save(any());
        }
    }

    @Nested
    @DisplayName("Anniversary.buildText — склонение чисел")
    class BuildTextPluralizationTests {

        private PlantAnniversaryService.Anniversary anniversary(int years, long waterings) {
            return new PlantAnniversaryService.Anniversary(1L, "Тестовая", 1L, 100L, years, waterings, 2026);
        }

        @Test
        @DisplayName("should_use_singular_year_form_for_one_year")
        void should_use_singular_year_form_for_one_year() {
            assertThat(anniversary(1, 5).buildText()).contains("1 год");
        }

        @Test
        @DisplayName("should_use_few_year_form_for_two_to_four_years")
        void should_use_few_year_form_for_two_to_four_years() {
            assertThat(anniversary(3, 5).buildText()).contains("3 года");
        }

        @Test
        @DisplayName("should_use_many_year_form_for_five_years")
        void should_use_many_year_form_for_five_years() {
            assertThat(anniversary(5, 5).buildText()).contains("5 лет");
        }

        @Test
        @DisplayName("should_use_many_year_form_for_eleven_years_exception_to_singular_rule")
        void should_use_many_year_form_for_eleven_years_exception_to_singular_rule() {
            // 11 % 10 == 1, но 11 % 100 == 11 — попадает в исключение (mod100 == 11) → "лет", не "год".
            assertThat(anniversary(11, 0).buildText()).contains("11 лет");
        }

        @Test
        @DisplayName("should_use_singular_year_form_for_twenty_one_years")
        void should_use_singular_year_form_for_twenty_one_years() {
            // 21 % 10 == 1, 21 % 100 == 21 (не 11) → "год".
            assertThat(anniversary(21, 0).buildText()).contains("21 год");
        }

        @Test
        @DisplayName("should_use_many_year_form_for_twelve_years_exception_to_few_rule")
        void should_use_many_year_form_for_twelve_years_exception_to_few_rule() {
            // 12 % 10 == 2 (в диапазоне 2-4), но 12 % 100 == 12 (в 12..14) → "лет", не "года".
            assertThat(anniversary(12, 0).buildText()).contains("12 лет");
        }

        @Test
        @DisplayName("should_use_singular_waterings_form_for_one")
        void should_use_singular_waterings_form_for_one() {
            assertThat(anniversary(2, 1).buildText()).contains("1 раз");
        }

        @Test
        @DisplayName("should_use_few_waterings_form_for_three")
        void should_use_few_waterings_form_for_three() {
            assertThat(anniversary(2, 3).buildText()).contains("3 раза");
        }

        @Test
        @DisplayName("should_use_default_waterings_form_for_zero")
        void should_use_default_waterings_form_for_zero() {
            assertThat(anniversary(2, 0).buildText()).contains("0 раз");
        }

        @Test
        @DisplayName("should_include_plant_name_and_all_segments_in_final_text")
        void should_include_plant_name_and_all_segments_in_final_text() {
            String text = anniversary(2, 3).buildText();
            assertThat(text)
                    .contains("Тестовая")
                    .contains("2 года")
                    .contains("3 раза")
                    .startsWith("🎂");
        }
    }
}

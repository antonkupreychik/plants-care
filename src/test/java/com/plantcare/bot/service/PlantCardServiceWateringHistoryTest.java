package com.plantcare.bot.service;

import com.plantcare.core.service.CareHistoryService;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.UserService;

import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.enums.TaskType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты для отображения деталей полива (issue #71) в истории ухода.
 *
 * <p>Проверяет, что formatHistoryLine корректно показывает обильность и
 * сухость грунта для WATERING-записей, а старые/без-деталей записи остаются
 * в прежнем формате.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PlantCardService.formatHistoryLine — детали полива в истории (#71)")
class PlantCardServiceWateringHistoryTest {

    @Mock private PlantService plantService;
    @Mock private com.plantcare.core.repository.PlantRepository plantRepository;
    @Mock private MainMenuService mainMenuService;
    @Mock private UserService userService;
    @Mock private CareHistoryService careHistoryService;

    @InjectMocks
    private PlantCardService service;

    private final ZoneId tz = ZoneId.of("UTC");

    private CareHistory.CareHistoryBuilder watering() {
        return CareHistory.builder()
                .plant(Plant.builder().name("Монстера").build())
                .taskType(TaskType.WATERING)
                .doneAt(LocalDateTime.of(2026, 5, 1, 10, 0))
                .onTime(true);
    }

    @Nested
    @DisplayName("Записи с заполненными деталями полива")
    class WithDetails {

        @Test
        @DisplayName("HEAVY + DRY → «полил, обильно, земля сухая»")
        void shouldShowHeavyDry() {
            CareHistory h = watering().wasAbundant(true).soilWasDry(true).build();

            String line = service.formatHistoryLine(h, tz);

            assertThat(line).isEqualTo("01.05 — 💧 полил, обильно, земля сухая (вовремя)");
        }

        @Test
        @DisplayName("NORMAL + WET → «полил, обычно, земля влажная»")
        void shouldShowNormalWet() {
            CareHistory h = watering().wasAbundant(false).soilWasDry(false).build();

            String line = service.formatHistoryLine(h, tz);

            assertThat(line).isEqualTo("01.05 — 💧 полил, обычно, земля влажная (вовремя)");
        }

        @Test
        @DisplayName("HEAVY + soilWasDry=null (юзер ответил «не знаю») → только обильность")
        void shouldOmitSoilWhenUnknown() {
            CareHistory h = watering().wasAbundant(true).soilWasDry(null).build();

            String line = service.formatHistoryLine(h, tz);

            assertThat(line).isEqualTo("01.05 — 💧 полил, обильно (вовремя)");
            assertThat(line).doesNotContain("земля");
        }

        @Test
        @DisplayName("С опозданием — статус прежний, детали остаются")
        void shouldKeepLateStatusWithDetails() {
            CareHistory h = watering().wasAbundant(true).soilWasDry(true).onTime(false).build();

            String line = service.formatHistoryLine(h, tz);

            assertThat(line).contains("обильно").contains("земля сухая").contains("с опозданием");
        }
    }

    @Nested
    @DisplayName("Совместимость со старыми/bulk записями")
    class WithoutDetails {

        @Test
        @DisplayName("WATERING с обоими null (bulk-полив или запись до V10) — старый формат")
        void shouldKeepOldFormatWhenBothNull() {
            CareHistory h = watering().wasAbundant(null).soilWasDry(null).build();

            String line = service.formatHistoryLine(h, tz);

            assertThat(line).isEqualTo("01.05 — 💧 полил (вовремя)");
        }

        @Test
        @DisplayName("MISTING с было-бы-заполненными деталями — игнорируются (не WATERING)")
        void shouldIgnoreDetailsForNonWatering() {
            // Защита от теоретического поломанного состояния БД, когда поля
            // оказались заполнены для не-WATERING типа.
            CareHistory h = CareHistory.builder()
                    .plant(Plant.builder().name("Фикус").build())
                    .taskType(TaskType.MISTING)
                    .doneAt(LocalDateTime.of(2026, 5, 1, 10, 0))
                    .onTime(true)
                    .wasAbundant(true)
                    .soilWasDry(true)
                    .build();

            String line = service.formatHistoryLine(h, tz);

            assertThat(line).isEqualTo("01.05 — 💨 опрыскал (вовремя)");
            assertThat(line).doesNotContain("обильно").doesNotContain("земля");
        }

        @Test
        @DisplayName("FERTILIZING — старый формат")
        void shouldUseOldFormatForFertilizing() {
            CareHistory h = CareHistory.builder()
                    .plant(Plant.builder().name("Фикус").build())
                    .taskType(TaskType.FERTILIZING)
                    .doneAt(LocalDateTime.of(2026, 5, 1, 10, 0))
                    .onTime(true)
                    .build();

            String line = service.formatHistoryLine(h, tz);

            assertThat(line).isEqualTo("01.05 — 🌿 удобрил (вовремя)");
        }

        @Test
        @DisplayName("SOIL_CHECK — старый формат (детали полива не применимы)")
        void shouldUseOldFormatForSoilCheck() {
            CareHistory h = CareHistory.builder()
                    .plant(Plant.builder().name("Фикус").build())
                    .taskType(TaskType.SOIL_CHECK)
                    .doneAt(LocalDateTime.of(2026, 5, 1, 10, 0))
                    .onTime(true)
                    .note("soil:DRY")
                    .build();

            String line = service.formatHistoryLine(h, tz);

            assertThat(line).contains("проверил").doesNotContain("обильно");
        }
    }
}

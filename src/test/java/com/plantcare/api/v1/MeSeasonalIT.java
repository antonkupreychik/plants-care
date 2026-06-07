package com.plantcare.api.v1;

import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.SeasonalMode;
import com.plantcare.core.domain.enums.SeasonalOverride;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.LocationRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for per-season seasonal settings REST API (issue #256):
 * GET/PATCH /api/v1/me/seasonal and DELETE /api/v1/me/seasonal/{season}.
 *
 * <p>Covers the full stack (controller → SeasonalSettingsService → repository →
 * Postgres 16 via Testcontainers) and the AC: set multiplier, set interval, clear,
 * and influence on schedule recalculation (verified through the seasonal projection
 * of GET /api/v1/plants/{id}/schedules).
 */
@AutoConfigureMockMvc
class MeSeasonalIT extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlantRepository plantRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private CareScheduleRepository careScheduleRepository;

    @AfterEach
    void cleanup() {
        careScheduleRepository.deleteAll();
        plantRepository.deleteAll();
        locationRepository.deleteAll();
        userRepository.deleteAll();
    }

    // -----------------------------------------------------------------------
    // GET — read current per-season settings
    // -----------------------------------------------------------------------

    @Test
    void should_return_per_season_settings_when_get_seasonal() throws Exception {
        // arrange
        User user = userRepository.save(User.builder()
                .telegramChatId(9_001L)
                .timezone("Europe/Moscow")
                .seasonalEnabled(true)
                .seasonalMode(SeasonalMode.MULTIPLIER)
                .summerMultiplier(new BigDecimal("0.80"))
                .winterMultiplier(new BigDecimal("1.20"))
                .build());

        // act + assert
        mockMvc.perform(get("/api/v1/me/seasonal")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.mode").value("MULTIPLIER"))
                .andExpect(jsonPath("$.seasons[0].season").value("SUMMER"))
                .andExpect(jsonPath("$.seasons[0].multiplier").value(0.8))
                .andExpect(jsonPath("$.seasons[1].season").value("WINTER"))
                .andExpect(jsonPath("$.seasons[1].multiplier").value(1.2));
    }

    // -----------------------------------------------------------------------
    // PATCH — set multiplier for a season
    // -----------------------------------------------------------------------

    @Test
    void should_set_multiplier_for_season_when_patch_seasonal() throws Exception {
        // arrange
        User user = userRepository.save(User.builder()
                .telegramChatId(9_002L)
                .timezone("Europe/Moscow")
                .seasonalEnabled(true)
                .seasonalMode(SeasonalMode.MULTIPLIER)
                .summerMultiplier(new BigDecimal("0.80"))
                .build());

        String body = """
                {"season": "SUMMER", "multiplier": 0.6}
                """;

        // act
        mockMvc.perform(patch("/api/v1/me/seasonal")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seasons[0].season").value("SUMMER"))
                .andExpect(jsonPath("$.seasons[0].multiplier").value(0.6));

        // assert — persisted
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getSummerMultiplier())
                .isEqualByComparingTo(new BigDecimal("0.60"));
    }

    // -----------------------------------------------------------------------
    // PATCH — set fixed interval for a season
    // -----------------------------------------------------------------------

    @Test
    void should_set_interval_for_season_when_patch_seasonal() throws Exception {
        // arrange
        User user = userRepository.save(User.builder()
                .telegramChatId(9_003L)
                .timezone("Europe/Moscow")
                .seasonalEnabled(true)
                .seasonalMode(SeasonalMode.FIXED)
                .build());

        String body = """
                {"season": "WINTER", "intervalDays": 14}
                """;

        // act
        mockMvc.perform(patch("/api/v1/me/seasonal")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seasons[1].season").value("WINTER"))
                .andExpect(jsonPath("$.seasons[1].intervalDays").value(14));

        // assert — persisted
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getWinterIntervalOverrideDays())
                .isEqualTo(14);
    }

    // -----------------------------------------------------------------------
    // DELETE — clear interval returns season to default
    // -----------------------------------------------------------------------

    @Test
    void should_clear_interval_when_delete_seasonal_season() throws Exception {
        // arrange — season has a fixed interval that we will clear
        User user = userRepository.save(User.builder()
                .telegramChatId(9_004L)
                .timezone("Europe/Moscow")
                .seasonalEnabled(true)
                .seasonalMode(SeasonalMode.FIXED)
                .summerIntervalOverrideDays(5)
                .build());

        // act
        mockMvc.perform(delete("/api/v1/me/seasonal/SUMMER")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seasons[0].season").value("SUMMER"))
                .andExpect(jsonPath("$.seasons[0].intervalDays").doesNotExist());

        // assert — interval reset to null (default behaviour)
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getSummerIntervalOverrideDays())
                .isNull();
    }

    @Test
    void should_be_idempotent_when_delete_already_cleared_interval() throws Exception {
        // arrange — no interval set
        User user = userRepository.save(User.builder()
                .telegramChatId(9_005L)
                .timezone("Europe/Moscow")
                .seasonalEnabled(true)
                .seasonalMode(SeasonalMode.FIXED)
                .build());

        // act + assert — repeated DELETE still 200
        mockMvc.perform(delete("/api/v1/me/seasonal/WINTER")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/me/seasonal/WINTER")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seasons[1].intervalDays").doesNotExist());
    }

    // -----------------------------------------------------------------------
    // Influence on schedule — setting a multiplier changes the seasonal projection
    // of GET /api/v1/plants/{id}/schedules (same recalculation as the bot).
    // -----------------------------------------------------------------------

    @Test
    void should_affect_schedule_recalculation_when_multiplier_changed_via_rest() throws Exception {
        // arrange — base interval 10, summer × 0.8 default = 8
        User user = userRepository.save(User.builder()
                .telegramChatId(9_006L)
                .timezone("Asia/Almaty")
                .seasonalEnabled(true)
                .seasonalMode(SeasonalMode.MULTIPLIER)
                .summerMultiplier(new BigDecimal("0.80"))
                .winterMultiplier(new BigDecimal("1.20"))
                .build());
        long plantId = givenPlantWithWateringSchedule(user, 10).getId();

        // sanity — summer projection is 8 before the change
        mockMvc.perform(get("/api/v1/plants/" + plantId + "/schedules")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seasonal.summerIntervalDays").value(8));

        // act — change summer multiplier to 0.5 via REST
        mockMvc.perform(patch("/api/v1/me/seasonal")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"season\": \"SUMMER\", \"multiplier\": 0.5}"))
                .andExpect(status().isOk());

        // assert — schedule projection now reflects 10 × 0.5 = 5
        mockMvc.perform(get("/api/v1/plants/" + plantId + "/schedules")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seasonal.active").value(true))
                .andExpect(jsonPath("$[0].seasonal.summerIntervalDays").value(5))
                .andExpect(jsonPath("$[0].seasonal.winterIntervalDays").value(12));
    }

    // -----------------------------------------------------------------------
    // Validation — out-of-range value rejected with 400
    // -----------------------------------------------------------------------

    @Test
    void should_reject_out_of_range_multiplier_with_400() throws Exception {
        // arrange
        User user = userRepository.save(User.builder()
                .telegramChatId(9_007L)
                .timezone("Europe/Moscow")
                .seasonalEnabled(true)
                .build());

        // act + assert — multiplier 5.0 exceeds maximum 1.5
        mockMvc.perform(patch("/api/v1/me/seasonal")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"season\": \"SUMMER\", \"multiplier\": 5.0}"))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private Plant givenPlantWithWateringSchedule(User user, int baseIntervalDays) {
        Location location = locationRepository.save(Location.builder()
                .user(user)
                .name("Test Location")
                .defaultLocation(true)
                .build());

        Plant plant = plantRepository.save(Plant.builder()
                .user(user)
                .location(location)
                .name("Test Plant")
                .seasonalOverride(SeasonalOverride.INHERIT)
                .build());

        careScheduleRepository.save(com.plantcare.core.domain.CareSchedule.builder()
                .plant(plant)
                .taskType(com.plantcare.core.domain.enums.TaskType.WATERING)
                .intervalDays(baseIntervalDays)
                .nextDueAt(LocalDateTime.now().plusDays(baseIntervalDays))
                .active(true)
                .build());

        return plant;
    }
}

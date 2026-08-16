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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the seasonal schedule preview fields introduced in issue #188.
 *
 * <p>Covers GET /api/v1/plants/{id}/schedules → seasonal.*
 * and PUT /api/v1/plants/{id}/schedules/{type} with seasonalOverride on the full stack
 * (controller → service → repository → Postgres 16 via Testcontainers).
 */
@AutoConfigureMockMvc
class PlantScheduleSeasonalIT extends IntegrationTestBase {

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
    // a) seasonal disabled globally
    // -----------------------------------------------------------------------

    @Test
    void should_return_seasonal_inactive_with_equal_intervals_when_seasonal_disabled() throws Exception {
        // arrange
        User user = userRepository.save(User.builder()
                .telegramChatId(8_001L)
                .timezone("Europe/Moscow")
                .seasonalEnabled(false)
                .build());

        long plantId = givenPlantWithWateringSchedule(user, 7).getId();

        // act + assert
        mockMvc.perform(get("/api/v1/plants/" + plantId + "/schedules")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("WATERING"))
                .andExpect(jsonPath("$[0].seasonal.active").value(false))
                .andExpect(jsonPath("$[0].seasonal.summerIntervalDays").value(7))
                .andExpect(jsonPath("$[0].seasonal.winterIntervalDays").value(7));
    }

    // -----------------------------------------------------------------------
    // b) seasonal enabled, MULTIPLIER mode
    // -----------------------------------------------------------------------

    @Test
    void should_return_multiplied_seasonal_intervals_when_multiplier_mode_enabled() throws Exception {
        // arrange — summer × 0.5 = 5, winter × 2.0 = 20 (base = 10)
        User user = userRepository.save(User.builder()
                .telegramChatId(8_002L)
                .timezone("Europe/Moscow")
                .seasonalEnabled(true)
                .seasonalMode(SeasonalMode.MULTIPLIER)
                .summerMultiplier(new BigDecimal("0.50"))
                .winterMultiplier(new BigDecimal("2.00"))
                .build());

        long plantId = givenPlantWithWateringSchedule(user, 10).getId();

        // act + assert
        mockMvc.perform(get("/api/v1/plants/" + plantId + "/schedules")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seasonal.active").value(true))
                .andExpect(jsonPath("$[0].seasonal.summerIntervalDays").value(5))
                .andExpect(jsonPath("$[0].seasonal.winterIntervalDays").value(20));
    }

    // -----------------------------------------------------------------------
    // c) per-plant OFF override disables seasonal even when user has it on
    // -----------------------------------------------------------------------

    @Test
    void should_return_seasonal_inactive_after_per_plant_off_override() throws Exception {
        // arrange — юзер с включённой сезонностью, но растение переопределим в OFF
        User user = userRepository.save(User.builder()
                .telegramChatId(8_003L)
                .timezone("Europe/Moscow")
                .seasonalEnabled(true)
                .seasonalMode(SeasonalMode.MULTIPLIER)
                .summerMultiplier(new BigDecimal("0.50"))
                .winterMultiplier(new BigDecimal("2.00"))
                .build());

        Plant plant = givenPlantWithWateringSchedule(user, 7);
        long plantId = plant.getId();

        String body = """
                {"every": 7, "unit": "DAY", "enabled": true, "seasonalOverride": "OFF"}
                """;

        // act — PUT с seasonalOverride=OFF
        mockMvc.perform(put("/api/v1/plants/" + plantId + "/schedules/WATERING")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seasonal.active").value(false))
                .andExpect(jsonPath("$.seasonal.summerIntervalDays").value(7))
                .andExpect(jsonPath("$.seasonal.winterIntervalDays").value(7));

        // assert — повторный GET тоже возвращает inactive
        mockMvc.perform(get("/api/v1/plants/" + plantId + "/schedules")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seasonal.active").value(false));
    }

    // -----------------------------------------------------------------------
    // d) per-plant ON override enables seasonal even when user has it off
    // -----------------------------------------------------------------------

    @Test
    void should_return_seasonal_active_after_per_plant_on_override() throws Exception {
        // arrange — глобально выкл, но у растения будет ON
        User user = userRepository.save(User.builder()
                .telegramChatId(8_004L)
                .timezone("Europe/Moscow")
                .seasonalEnabled(false)
                .seasonalMode(SeasonalMode.MULTIPLIER)
                .summerMultiplier(new BigDecimal("0.50"))
                .winterMultiplier(new BigDecimal("2.00"))
                .build());

        Plant plant = givenPlantWithWateringSchedule(user, 10);
        long plantId = plant.getId();

        String body = """
                {"every": 10, "unit": "DAY", "enabled": true, "seasonalOverride": "ON"}
                """;

        // act
        mockMvc.perform(put("/api/v1/plants/" + plantId + "/schedules/WATERING")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seasonal.active").value(true))
                // summer × 0.5 = 5, winter × 2.0 = 20
                .andExpect(jsonPath("$.seasonal.summerIntervalDays").value(5))
                .andExpect(jsonPath("$.seasonal.winterIntervalDays").value(20));
    }

    // -----------------------------------------------------------------------
    // e) non-UTC timezone — Asia/Almaty — seasonal works without error
    // -----------------------------------------------------------------------

    @Test
    void should_return_seasonal_info_correctly_for_non_utc_timezone() throws Exception {
        // arrange
        User user = userRepository.save(User.builder()
                .telegramChatId(8_005L)
                .timezone("Asia/Almaty")
                .seasonalEnabled(true)
                .seasonalMode(SeasonalMode.MULTIPLIER)
                .summerMultiplier(new BigDecimal("0.80"))
                .winterMultiplier(new BigDecimal("1.20"))
                .build());

        long plantId = givenPlantWithWateringSchedule(user, 10).getId();

        // act + assert — проверяем что seasonal корректно заполнен, нет ошибки 500
        mockMvc.perform(get("/api/v1/plants/" + plantId + "/schedules")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seasonal.active").value(true))
                // summer × 0.80 = 8, winter × 1.20 = 12
                .andExpect(jsonPath("$[0].seasonal.summerIntervalDays").value(8))
                .andExpect(jsonPath("$[0].seasonal.winterIntervalDays").value(12));
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    /**
     * Создаёт локацию, растение (INHERIT seasonalOverride) и активное WATERING-расписание
     * с заданным базовым интервалом. Возвращает сохранённое растение.
     */
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

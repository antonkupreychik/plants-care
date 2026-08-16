package com.plantcare.api.v1;

import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.LocationRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Интеграционные тесты для GET /api/v1/plants/{id}/history/summary
 * и GET /api/v1/plants/{id}/history?type=... (issue #206).
 *
 * <p>Полный стек: controller → service → repository → реальный Postgres 16.
 * Проверяет агрегацию на реальных данных: total, onTimeCount, onTimePercent, byType,
 * фильтрацию по типу, исключение SOIL_CHECK из byType.
 */
@AutoConfigureMockMvc
class PlantHistorySummaryIT extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private PlantRepository plantRepository;

    @Autowired
    private CareHistoryRepository careHistoryRepository;

    @AfterEach
    void cleanup() {
        careHistoryRepository.deleteAll();
        plantRepository.deleteAll();
        locationRepository.deleteAll();
        userRepository.deleteAll();
    }

    // -------------------------------------------------------------------------
    // GET /history/summary — happy path
    // -------------------------------------------------------------------------

    @Test
    void should_return_correct_summary_when_plant_has_mixed_history() throws Exception {
        // arrange — 3 WATERING + 2 MISTING; 4 из 5 записей on-time
        User user = saveUser(8_200_001L);
        Plant plant = savePlant(user, "Monstera");

        saveHistory(plant, TaskType.WATERING, true);
        saveHistory(plant, TaskType.WATERING, true);
        saveHistory(plant, TaskType.WATERING, true);
        saveHistory(plant, TaskType.MISTING, true);
        saveHistory(plant, TaskType.MISTING, false);   // опоздание

        // act & assert
        mockMvc.perform(get("/api/v1/plants/{id}/history/summary", plant.getId())
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.onTimeCount").value(4))
                .andExpect(jsonPath("$.onTimePercent").value(80))
                .andExpect(jsonPath("$.byType.WATER").value(3))
                .andExpect(jsonPath("$.byType.SPRAY").value(2))
                .andExpect(jsonPath("$.byType.FERTILIZE").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // GET /history/summary — edge: пустая история
    // -------------------------------------------------------------------------

    @Test
    void should_return_zeros_and_empty_byType_when_history_is_empty() throws Exception {
        // arrange — растение без единой записи
        User user = saveUser(8_200_002L);
        Plant plant = savePlant(user, "Ficus");

        // act & assert
        mockMvc.perform(get("/api/v1/plants/{id}/history/summary", plant.getId())
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.onTimeCount").value(0))
                .andExpect(jsonPath("$.onTimePercent").value(0))
                .andExpect(jsonPath("$.byType").isEmpty());
    }

    // -------------------------------------------------------------------------
    // GET /history?type=WATER — фильтр возвращает только указанный тип
    // -------------------------------------------------------------------------

    @Test
    void should_return_only_watering_records_when_type_filter_applied() throws Exception {
        // arrange — 3 WATERING + 2 MISTING
        User user = saveUser(8_200_003L);
        Plant plant = savePlant(user, "Cactus");

        saveHistory(plant, TaskType.WATERING, true);
        saveHistory(plant, TaskType.WATERING, true);
        saveHistory(plant, TaskType.WATERING, false);
        saveHistory(plant, TaskType.MISTING, true);
        saveHistory(plant, TaskType.MISTING, true);

        // act & assert — ?type=WATER → только 3 записи полива в items
        mockMvc.perform(get("/api/v1/plants/{id}/history", plant.getId())
                        .queryParam("type", "WATER")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].type").value("WATER"))
                .andExpect(jsonPath("$.items[1].type").value("WATER"))
                .andExpect(jsonPath("$.items[2].type").value("WATER"));
    }

    @Test
    void should_return_all_records_when_no_type_filter() throws Exception {
        // arrange — 3 WATERING + 2 MISTING
        User user = saveUser(8_200_004L);
        Plant plant = savePlant(user, "Aloe");

        saveHistory(plant, TaskType.WATERING, true);
        saveHistory(plant, TaskType.WATERING, true);
        saveHistory(plant, TaskType.WATERING, true);
        saveHistory(plant, TaskType.MISTING, true);
        saveHistory(plant, TaskType.MISTING, true);

        // act & assert — без фильтра → все 5 записей
        mockMvc.perform(get("/api/v1/plants/{id}/history", plant.getId())
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5));
    }

    @Test
    void should_include_soil_check_in_history_items_when_present() throws Exception {
        // arrange — issue #222: SOIL_CHECK теперь публичный тип и попадает в выдачу
        User user = saveUser(8_200_009L);
        Plant plant = savePlant(user, "Sansevieria");

        saveHistory(plant, TaskType.WATERING, true);
        saveHistory(plant, TaskType.SOIL_CHECK, true);

        // act & assert — обе записи в items, фильтр ?type=SOIL_CHECK даёт только проверку
        mockMvc.perform(get("/api/v1/plants/{id}/history", plant.getId())
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));

        mockMvc.perform(get("/api/v1/plants/{id}/history", plant.getId())
                        .queryParam("type", "SOIL_CHECK")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].type").value("SOIL_CHECK"));
    }

    // -------------------------------------------------------------------------
    // GET /history/summary — SOIL_CHECK скрыт из byType (total включает его)
    // -------------------------------------------------------------------------

    @Test
    void should_exclude_soil_check_from_byType_but_include_in_total() throws Exception {
        // arrange — 2 WATERING + 1 SOIL_CHECK
        // Поведение сервиса: total = countActiveByPlantId (все типы, включая SOIL_CHECK),
        // byType = группировка с фильтрацией SOIL_CHECK в сервисе.
        User user = saveUser(8_200_005L);
        Plant plant = savePlant(user, "ZZ Plant");

        saveHistory(plant, TaskType.WATERING, true);
        saveHistory(plant, TaskType.WATERING, true);
        saveHistory(plant, TaskType.SOIL_CHECK, true);

        // act & assert
        mockMvc.perform(get("/api/v1/plants/{id}/history/summary", plant.getId())
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                // total включает SOIL_CHECK (сырой счётчик всех активных записей)
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.byType.WATER").value(2))
                // SOIL_CHECK не должен появляться в byType
                .andExpect(jsonPath("$.byType.SOIL_CHECK").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // GET /history/summary — 404 при обращении к чужому растению
    // -------------------------------------------------------------------------

    @Test
    void should_return_404_when_plant_belongs_to_another_user() throws Exception {
        // arrange
        User owner = saveUser(8_200_006L);
        User stranger = saveUser(8_200_007L);
        Plant ownerPlant = savePlant(owner, "Monstera");

        // act — stranger запрашивает растение owner'а → 404
        mockMvc.perform(get("/api/v1/plants/{id}/history/summary", ownerPlant.getId())
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(stranger.getId())))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private User saveUser(long chatId) {
        return userRepository.save(User.builder()
                .telegramChatId(chatId)
                .timezone("UTC")
                .blocked(false)
                .build());
    }

    private Plant savePlant(User user, String name) {
        Location location = locationRepository.findByUserIdAndDefaultLocationTrue(user.getId())
                .orElseGet(() -> locationRepository.save(Location.builder()
                        .user(user)
                        .name(Location.DEFAULT_NAME)
                        .emoji(Location.DEFAULT_EMOJI)
                        .defaultLocation(true)
                        .build()));
        return plantRepository.save(Plant.builder()
                .user(user)
                .location(location)
                .name(name)
                .build());
    }

    private void saveHistory(Plant plant, TaskType type, boolean onTime) {
        careHistoryRepository.save(CareHistory.builder()
                .plant(plant)
                .taskType(type)
                .doneAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS))
                .onTime(onTime)
                .build());
    }
}

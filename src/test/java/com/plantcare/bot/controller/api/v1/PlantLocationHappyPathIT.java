package com.plantcare.bot.controller.api.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.repository.LocationRepository;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.repository.UserRepository;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Happy-path integration test for the Plants + Locations REST API (issue #85).
 *
 * <p>Runs against a real Postgres 16 container (via {@link IntegrationTestBase}).
 * Exercises the full stack: controller → service → repository → DB.
 *
 * <p>Covers:
 * <ul>
 *   <li>POST /api/v1/locations → 201</li>
 *   <li>POST /api/v1/plants with locationId → 201</li>
 *   <li>GET /api/v1/plants/{id} → 200 with correct name</li>
 *   <li>DELETE /api/v1/plants/{id} → 204 (soft-delete)</li>
 *   <li>GET /api/v1/plants/{id} after delete → 404 (archived plant not visible)</li>
 * </ul>
 */
@AutoConfigureMockMvc
class PlantLocationHappyPathIT extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlantRepository plantRepository;

    @Autowired
    private LocationRepository locationRepository;

    @AfterEach
    void cleanup() {
        plantRepository.deleteAll();
        locationRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void should_create_location_and_plant_then_list_and_delete() throws Exception {
        // arrange — insert a test user into DB directly via repository
        User user = userRepository.save(User.builder()
                .telegramChatId(9_000_001L)
                .username("it_test_user")
                .timezone("Europe/Moscow")
                .build());
        long userId = user.getId();

        // act 1 — POST /api/v1/locations
        String locationBody = """
                {"name": "Windowsill", "emoji": "🪟"}
                """;

        MvcResult locationResult = mockMvc.perform(post("/api/v1/locations")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(locationBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Windowsill"))
                .andReturn();

        JsonNode locationJson = objectMapper.readTree(locationResult.getResponse().getContentAsString());
        long locationId = locationJson.get("id").asLong();
        assertThat(locationId).isPositive();

        // act 2 — POST /api/v1/plants with locationId
        String plantBody = String.format("""
                {"name": "Cactus", "locationId": %d}
                """, locationId);

        MvcResult plantResult = mockMvc.perform(post("/api/v1/plants")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(plantBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Cactus"))
                .andExpect(jsonPath("$.locationId").value(locationId))
                .andReturn();

        JsonNode plantJson = objectMapper.readTree(plantResult.getResponse().getContentAsString());
        long plantId = plantJson.get("id").asLong();
        assertThat(plantId).isPositive();

        // act 3 — GET /api/v1/plants/{plantId}
        mockMvc.perform(get("/api/v1/plants/" + plantId)
                        .header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(plantId))
                .andExpect(jsonPath("$.name").value("Cactus"))
                .andExpect(jsonPath("$.archived").value(false));

        // act 4 — DELETE /api/v1/plants/{plantId} (soft-delete)
        mockMvc.perform(delete("/api/v1/plants/" + plantId)
                        .header("X-User-Id", userId))
                .andExpect(status().isNoContent());

        // assert — GET after soft-delete must return 404
        mockMvc.perform(get("/api/v1/plants/" + plantId)
                        .header("X-User-Id", userId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        // assert — archivedAt is set in the DB
        var archived = plantRepository.findById(plantId);
        assertThat(archived).isPresent();
        assertThat(archived.get().getArchivedAt()).isNotNull();
        assertThat(archived.get().isArchived()).isTrue();
    }
}

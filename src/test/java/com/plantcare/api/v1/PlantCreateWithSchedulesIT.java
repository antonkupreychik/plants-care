package com.plantcare.api.v1;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for POST /api/v1/plants with the optional {@code schedules} field.
 *
 * <p>Covers issue #216: when a caller supplies explicit schedule types,
 * only those are created as active; all remaining standard types are
 * created with {@code active=false} and a default interval.
 */
@AutoConfigureMockMvc
class PlantCreateWithSchedulesIT extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlantRepository plantRepository;

    @Autowired
    private CareScheduleRepository scheduleRepository;

    @AfterEach
    void cleanup() {
        scheduleRepository.deleteAll();
        plantRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ==================== happy path ====================

    @Test
    void should_create_active_watering_schedule_and_inactive_others_when_single_watering_schedule_supplied() throws Exception {
        // arrange
        User user = userRepository.save(User.builder()
                .telegramChatId(9_216_001L)
                .username("it_schedules_user1")
                .timezone("UTC")
                .build());

        String body = """
                {
                  "name": "Cactus",
                  "schedules": [
                    {"type": "WATERING", "every": 5, "unit": "DAY", "amountMl": 150}
                  ]
                }
                """;

        // act
        MvcResult result = mockMvc.perform(post("/api/v1/plants")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Cactus"))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        long plantId = json.get("id").asLong();

        // assert — plant created
        assertThat(plantId).isPositive();

        List<CareSchedule> all = scheduleRepository.findAllByPlantId(plantId);
        Map<TaskType, CareSchedule> byType = all.stream()
                .collect(Collectors.toMap(CareSchedule::getTaskType, s -> s));

        // all 4 standard types must be present
        assertThat(byType).containsOnlyKeys(
                TaskType.WATERING, TaskType.MISTING, TaskType.FERTILIZING, TaskType.SOIL_CHECK);

        // watering: active, interval=5, amountMl=150
        CareSchedule watering = byType.get(TaskType.WATERING);
        assertThat(watering.isActive()).isTrue();
        assertThat(watering.getIntervalDays()).isEqualTo(5);
        assertThat(watering.getAmountMl()).isEqualTo(150);

        // rest: inactive
        assertThat(byType.get(TaskType.MISTING).isActive()).isFalse();
        assertThat(byType.get(TaskType.FERTILIZING).isActive()).isFalse();
        assertThat(byType.get(TaskType.SOIL_CHECK).isActive()).isFalse();
    }

    // ==================== edge case: no schedules field ====================

    @Test
    void should_create_4_schedules_with_watering_active_when_schedules_field_absent() throws Exception {
        // arrange
        User user = userRepository.save(User.builder()
                .telegramChatId(9_216_002L)
                .username("it_schedules_user2")
                .timezone("UTC")
                .build());

        String body = """
                {"name": "Monstera"}
                """;

        // act
        MvcResult result = mockMvc.perform(post("/api/v1/plants")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        long plantId = json.get("id").asLong();

        // assert
        List<CareSchedule> all = scheduleRepository.findAllByPlantId(plantId);
        Map<TaskType, CareSchedule> byType = all.stream()
                .collect(Collectors.toMap(CareSchedule::getTaskType, s -> s));

        assertThat(byType).containsOnlyKeys(
                TaskType.WATERING, TaskType.MISTING, TaskType.FERTILIZING, TaskType.SOIL_CHECK);

        // default behaviour: WATERING active, others inactive
        assertThat(byType.get(TaskType.WATERING).isActive()).isTrue();
        assertThat(byType.get(TaskType.MISTING).isActive()).isFalse();
        assertThat(byType.get(TaskType.FERTILIZING).isActive()).isFalse();
        assertThat(byType.get(TaskType.SOIL_CHECK).isActive()).isFalse();

        // all schedules have a sensible interval
        assertThat(byType.get(TaskType.WATERING).getIntervalDays()).isPositive();
        assertThat(byType.get(TaskType.MISTING).getIntervalDays()).isPositive();
        assertThat(byType.get(TaskType.FERTILIZING).getIntervalDays()).isPositive();
        assertThat(byType.get(TaskType.SOIL_CHECK).getIntervalDays()).isPositive();
    }

    // ==================== edge case: multiple schedules ====================

    @Test
    void should_mark_only_specified_types_active_when_multiple_schedules_supplied() throws Exception {
        // arrange
        User user = userRepository.save(User.builder()
                .telegramChatId(9_216_003L)
                .username("it_schedules_user3")
                .timezone("UTC")
                .build());

        String body = """
                {
                  "name": "Fern",
                  "schedules": [
                    {"type": "WATERING",    "every": 3, "unit": "DAY"},
                    {"type": "FERTILIZING", "every": 14, "unit": "DAY"}
                  ]
                }
                """;

        // act
        MvcResult result = mockMvc.perform(post("/api/v1/plants")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        long plantId = json.get("id").asLong();

        // assert
        List<CareSchedule> all = scheduleRepository.findAllByPlantId(plantId);
        Map<TaskType, CareSchedule> byType = all.stream()
                .collect(Collectors.toMap(CareSchedule::getTaskType, s -> s));

        assertThat(byType).containsOnlyKeys(
                TaskType.WATERING, TaskType.MISTING, TaskType.FERTILIZING, TaskType.SOIL_CHECK);

        // explicitly supplied → active
        assertThat(byType.get(TaskType.WATERING).isActive()).isTrue();
        assertThat(byType.get(TaskType.WATERING).getIntervalDays()).isEqualTo(3);

        assertThat(byType.get(TaskType.FERTILIZING).isActive()).isTrue();
        assertThat(byType.get(TaskType.FERTILIZING).getIntervalDays()).isEqualTo(14);

        // not supplied → inactive
        assertThat(byType.get(TaskType.MISTING).isActive()).isFalse();
        assertThat(byType.get(TaskType.SOIL_CHECK).isActive()).isFalse();
    }

    // ==================== timezone: non-UTC user ====================

    @Test
    void should_create_schedule_with_positive_nextDueAt_when_user_has_non_utc_timezone() throws Exception {
        // arrange
        User user = userRepository.save(User.builder()
                .telegramChatId(9_216_004L)
                .username("it_schedules_user4")
                .timezone("Asia/Almaty")
                .build());

        String body = """
                {
                  "name": "Aloe",
                  "schedules": [
                    {"type": "WATERING", "every": 7, "unit": "DAY"}
                  ]
                }
                """;

        // act
        MvcResult result = mockMvc.perform(post("/api/v1/plants")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Aloe"))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        long plantId = json.get("id").asLong();

        // assert
        List<CareSchedule> all = scheduleRepository.findAllByPlantId(plantId);
        Map<TaskType, CareSchedule> byType = all.stream()
                .collect(Collectors.toMap(CareSchedule::getTaskType, s -> s));

        CareSchedule watering = byType.get(TaskType.WATERING);
        assertThat(watering).isNotNull();
        assertThat(watering.isActive()).isTrue();
        assertThat(watering.getIntervalDays()).isEqualTo(7);
        assertThat(watering.getNextDueAt()).isAfter(LocalDateTime.now(ZoneOffset.UTC));
    }
}

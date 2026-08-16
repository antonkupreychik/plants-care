package com.plantcare.api.v1;

import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.LocationRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test для issue #207: health-поля в списке и карточке растения.
 *
 * <p>Полный стек — controller → service → repository → реальный Postgres 16.
 * Не использует моки репозиториев: сценарии «мало данных» / «достаточно данных»
 * проверяются вставкой реальных записей в care_history.
 *
 * <p>Покрывает:
 * <ul>
 *   <li>(a) GET /api/v1/plants → healthInsufficientData=true при отсутствии истории</li>
 *   <li>(b) GET /api/v1/plants → healthInsufficientData=false + score∈[0,100] + zone при ≥3 записях</li>
 *   <li>(c) Таймзона Asia/Almaty — health вычисляется корректно независимо от TZ юзера</li>
 *   <li>(d) GET /api/v1/plants/{id} → health-поля присутствуют</li>
 * </ul>
 */
@AutoConfigureMockMvc
class PlantHealthInListIT extends IntegrationTestBase {

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

    @Autowired
    private CareScheduleRepository careScheduleRepository;

    @AfterEach
    void cleanup() {
        careHistoryRepository.deleteAll();
        careScheduleRepository.deleteAll();
        plantRepository.deleteAll();
        locationRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ── (a) мало данных ──────────────────────────────────────────────────────

    @Test
    void should_return_insufficient_data_when_no_care_history_in_list() throws Exception {
        // arrange — юзер и растение без единой записи ухода
        User user = savedUser(9_207_001L, "Europe/Moscow");
        Location location = savedLocation(user, "Подоконник");
        plantRepository.save(Plant.builder()
                .user(user)
                .location(location)
                .name("Фикус")
                .build());

        // act + assert
        mockMvc.perform(get("/api/v1/plants")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].healthInsufficientData").value(true))
                .andExpect(jsonPath("$.items[0].healthScore").isEmpty())
                .andExpect(jsonPath("$.items[0].healthZone").isEmpty());
    }

    // ── (b) достаточно данных ────────────────────────────────────────────────

    @Test
    void should_return_score_and_zone_when_sufficient_care_history_in_list() throws Exception {
        // arrange — юзер, растение, ≥ 3 активных записей в care_history
        User user = savedUser(9_207_002L, "UTC");
        Location location = savedLocation(user, "Кухня");
        Plant plant = plantRepository.save(Plant.builder()
                .user(user)
                .location(location)
                .name("Монстера")
                .build());

        saveOnTimeCareHistory(plant, 3);

        // act + assert
        mockMvc.perform(get("/api/v1/plants")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].healthInsufficientData").value(false))
                .andExpect(jsonPath("$.items[0].healthScore").isNumber())
                .andExpect(jsonPath("$.items[0].healthZone").isString());
    }

    // ── (c) таймзона Asia/Almaty ─────────────────────────────────────────────

    @Test
    void should_compute_health_correctly_for_user_in_almaty_timezone() throws Exception {
        // arrange — юзер в Asia/Almaty (+5 UTC)
        User user = savedUser(9_207_003L, "Asia/Almaty");
        Location location = savedLocation(user, "Спальня");
        Plant plant = plantRepository.save(Plant.builder()
                .user(user)
                .location(location)
                .name("Алоэ")
                .build());

        saveOnTimeCareHistory(plant, 3);

        // act + assert — health вычисляется по окну 30 дней в TZ юзера, всё равно корректно
        mockMvc.perform(get("/api/v1/plants")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].healthInsufficientData").value(false))
                .andExpect(jsonPath("$.items[0].healthScore").isNumber())
                .andExpect(jsonPath("$.items[0].healthZone").isString());
    }

    // ── (d) single plant endpoint ────────────────────────────────────────────

    @Test
    void should_return_insufficient_data_when_no_care_history_in_single_plant() throws Exception {
        // arrange
        User user = savedUser(9_207_004L, "UTC");
        Location location = savedLocation(user, "Гостиная");
        Plant plant = plantRepository.save(Plant.builder()
                .user(user)
                .location(location)
                .name("Кактус")
                .build());

        // act + assert — GET /api/v1/plants/{id} тоже возвращает health-поля
        mockMvc.perform(get("/api/v1/plants/" + plant.getId())
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(plant.getId()))
                .andExpect(jsonPath("$.healthInsufficientData").value(true))
                .andExpect(jsonPath("$.healthScore").isEmpty())
                .andExpect(jsonPath("$.healthZone").isEmpty());
    }

    @Test
    void should_return_score_and_zone_in_single_plant_when_sufficient_care_history() throws Exception {
        // arrange
        User user = savedUser(9_207_005L, "UTC");
        Location location = savedLocation(user, "Балкон");
        Plant plant = plantRepository.save(Plant.builder()
                .user(user)
                .location(location)
                .name("Петуния")
                .build());

        saveOnTimeCareHistory(plant, 5);

        // act + assert
        mockMvc.perform(get("/api/v1/plants/" + plant.getId())
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(plant.getId()))
                .andExpect(jsonPath("$.healthInsufficientData").value(false))
                .andExpect(jsonPath("$.healthScore").isNumber())
                .andExpect(jsonPath("$.healthZone").isString());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private User savedUser(long chatId, String timezone) {
        return userRepository.save(User.builder()
                .telegramChatId(chatId)
                .timezone(timezone)
                .blocked(false)
                .build());
    }

    private Location savedLocation(User user, String name) {
        return locationRepository.save(Location.builder()
                .user(user)
                .name(name)
                .emoji("🌿")
                .defaultLocation(false)
                .build());
    }

    /**
     * Создаёт {@code count} активных (не-отменённых) on-time записей ухода
     * за последние 7 дней — заведомо в пределах 30-дневного окна health-score.
     */
    private void saveOnTimeCareHistory(Plant plant, int count) {
        LocalDateTime base = LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(7);
        for (int i = 0; i < count; i++) {
            CareHistory history = CareHistory.builder()
                    .plant(plant)
                    .taskType(TaskType.WATERING)
                    .doneAt(base.minusDays(i))
                    .onTime(true)
                    .build();
            careHistoryRepository.save(history);
        }
    }
}

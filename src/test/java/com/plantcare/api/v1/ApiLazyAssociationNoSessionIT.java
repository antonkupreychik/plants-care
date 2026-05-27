package com.plantcare.api.v1;

import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.LocationRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.SpeciesRepository;
import com.plantcare.core.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Регрессия на {@code LazyInitializationException: could not initialize proxy – no Session}
 * в публичном REST API (OSIV выключен, {@code application.yml}).
 *
 * <p>Контроллеры мапят entity → DTO уже вне открытой транзакции, поэтому любой
 * доступ к ленивой {@code @ManyToOne}-связи без {@code JOIN FETCH}/{@code @EntityGraph}
 * падал 500. Полный стек (controller → service → repository → реальный Postgres),
 * метод НЕ {@code @Transactional} — сессия закрывается до маппинга, как в проде.
 *
 * <p>Покрывает:
 * <ul>
 *   <li>GET /api/v1/plants/{id} — {@code Plant.location} + {@code Plant.species};</li>
 *   <li>GET /api/v1/plants — пагинированный список (тот же маппинг по элементам);</li>
 *   <li>POST /api/v1/care-events идемпотентный повтор — {@code CareHistory.plant}
 *       из {@code findByClientId}.</li>
 * </ul>
 */
@AutoConfigureMockMvc
class ApiLazyAssociationNoSessionIT extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private SpeciesRepository speciesRepository;

    @Autowired
    private PlantRepository plantRepository;

    @Autowired
    private CareScheduleRepository careScheduleRepository;

    @Autowired
    private CareHistoryRepository careHistoryRepository;

    @AfterEach
    void cleanup() {
        careHistoryRepository.deleteAll();
        careScheduleRepository.deleteAll();
        plantRepository.deleteAll();
        speciesRepository.deleteAll();
        locationRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void should_return_location_and_species_names_when_get_plant() throws Exception {
        // arrange — растение со ВСЕМИ ленивыми связями (location обязателен, species опционален)
        User user = savedUser(9_100_001L);
        Location location = savedLocation(user, "Подоконник", "🪟");
        Species species = savedSpecies("Монстера");
        Plant plant = plantRepository.save(Plant.builder()
                .user(user)
                .location(location)
                .species(species)
                .name("Моня")
                .build());

        // act + assert — без fetch'а location.getName()/species.getName() дали бы 500
        mockMvc.perform(get("/api/v1/plants/" + plant.getId())
                        .header("X-User-Id", user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Моня"))
                .andExpect(jsonPath("$.locationName").value("Подоконник"))
                .andExpect(jsonPath("$.speciesName").value("Монстера"));
    }

    @Test
    void should_return_location_and_species_names_when_list_plants() throws Exception {
        // arrange
        User user = savedUser(9_100_002L);
        Location location = savedLocation(user, "Кухня", "🍽");
        Species species = savedSpecies("Фикус");
        plantRepository.save(Plant.builder()
                .user(user)
                .location(location)
                .species(species)
                .name("Фикусик")
                .build());

        // act + assert — пагинированный список мапит те же ленивые связи поэлементно
        mockMvc.perform(get("/api/v1/plants")
                        .header("X-User-Id", user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Фикусик"))
                .andExpect(jsonPath("$.items[0].locationName").value("Кухня"))
                .andExpect(jsonPath("$.items[0].speciesName").value("Фикус"));
    }

    @Test
    void should_return_plant_name_on_idempotent_care_event_replay() throws Exception {
        // arrange — растение с активным расписанием полива
        User user = savedUser(9_100_003L);
        Location location = savedLocation(user, "Спальня", "🛏");
        Plant plant = plantRepository.save(Plant.builder()
                .user(user)
                .location(location)
                .name("Алоэ")
                .build());
        careScheduleRepository.save(CareSchedule.builder()
                .plant(plant)
                .taskType(TaskType.WATERING)
                .intervalDays(7)
                .nextDueAt(LocalDateTime.now())
                .active(true)
                .build());

        String body = String.format("""
                {"plantId": %d, "type": "WATER", "performedAt": "2026-05-22T10:00:00Z", "clientId": "idem-key-1"}
                """, plant.getId());

        // act 1 — первый POST: plant загружен сервисом, маппинг проходит
        mockMvc.perform(post("/api/v1/care-events")
                        .header("X-Chat-Id", user.getTelegramChatId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plantName").value("Алоэ"));

        // assert — повтор с тем же clientId возвращает существующую запись;
        // её plant приходит из findByClientId и без @EntityGraph дал бы no Session
        mockMvc.perform(post("/api/v1/care-events")
                        .header("X-Chat-Id", user.getTelegramChatId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plantName").value("Алоэ"));
    }

    @Test
    void should_return_plant_name_when_get_plant_history() throws Exception {
        // arrange — растение с записью истории; нативный листинг истории plant не фетчит
        User user = savedUser(9_100_004L);
        Location location = savedLocation(user, "Коридор", "🚪");
        Plant plant = plantRepository.save(Plant.builder()
                .user(user)
                .location(location)
                .name("Замиокулькас")
                .build());

        CareHistory history = new CareHistory();
        history.setPlant(plant);
        history.setTaskType(TaskType.WATERING);
        history.setDoneAt(LocalDateTime.now());
        history.setOnTime(true);
        careHistoryRepository.save(history);

        // act + assert — history.getPlant().getName() на ленивом прокси дал бы no-session
        mockMvc.perform(get("/api/v1/plants/" + plant.getId() + "/history?limit=10&offset=0")
                        .header("X-Chat-Id", user.getTelegramChatId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].plantName").value("Замиокулькас"));
    }

    // ===================== helpers =====================

    private User savedUser(long chatId) {
        return userRepository.save(User.builder()
                .telegramChatId(chatId)
                .timezone("Europe/Moscow")
                .blocked(false)
                .build());
    }

    private Location savedLocation(User user, String name, String emoji) {
        return locationRepository.save(Location.builder()
                .user(user)
                .name(name)
                .emoji(emoji)
                .defaultLocation(false)
                .build());
    }

    private Species savedSpecies(String name) {
        return speciesRepository.save(Species.builder()
                .name(name)
                .popularity(0)
                .build());
    }
}

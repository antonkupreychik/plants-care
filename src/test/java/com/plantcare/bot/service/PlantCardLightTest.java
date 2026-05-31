package com.plantcare.bot.service;

import com.plantcare.core.service.CareHistoryService;
import com.plantcare.core.service.HealthScoreService;
import com.plantcare.core.service.PlantEventService;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.SpeciesFactService;
import com.plantcare.core.service.UserService;

import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.LightPreference;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.seasonal.service.SeasonalIntervalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * Юнит-тесты строки освещения вида в детальной карточке растения (issue #135,
 * рендер в {@code buildDetailedCardText}).
 *
 * <p>Seam повторяет {@link PlantCardToxicityTest}: приватный метод рендера
 * тестируется через публичный {@code showPlantCard}, Telegram-клиент мокается,
 * перехватывается отправленное {@link SendMessage} и инспектируется его текст.
 * Инкапсуляция не ломается — используем уже принятый в проекте подход.
 */
@ExtendWith(MockitoExtension.class)
class PlantCardLightTest {

    private static final String LIGHT_PREFIX = "☀️ Освещение: ";

    @Mock private PlantService plantService;
    @Mock private PlantRepository plantRepository;
    @Mock private com.plantcare.core.repository.CareScheduleRepository careScheduleRepository;
    @Mock private MainMenuService mainMenuService;
    @Mock private UserService userService;
    @Mock private CareHistoryService careHistoryService;
    @Mock private HealthScoreService healthScoreService;
    @Mock private PlantEventService plantEventService;
    @Mock private SpeciesFactService speciesFactService;
    @Mock private SeasonalIntervalService seasonalIntervalService;

    @Mock private TelegramClient client;

    private PlantCardService cardService;

    @BeforeEach
    void setUp() {
        cardService = new PlantCardService(
                plantService, plantRepository, careScheduleRepository, mainMenuService, userService,
                careHistoryService, healthScoreService, plantEventService, speciesFactService,
                seasonalIntervalService);

        // health-score не важен для строки освещения — возвращаем «мало данных».
        lenient().when(healthScoreService.computeForPlant(any()))
                .thenReturn(HealthScoreService.HealthScore.insufficient());
    }

    @Test
    void should_show_light_line_with_phrase_when_species_has_light_preference() throws Exception {
        // arrange
        Species species = givenSpecies(100L);
        species.setLightPreference(LightPreference.BRIGHT);
        givenCardFor(species);

        // act
        cardService.showPlantCard(givenUser(1L, 555L), 10L, null, PlantCardService.BACK_TO_LIST, client);

        // assert
        assertThat(cardText())
                .contains(LIGHT_PREFIX + LightPreferenceText.phrase(LightPreference.BRIGHT));
    }

    @Test
    void should_not_show_light_line_when_species_light_preference_is_null() throws Exception {
        // arrange — освещение неизвестно: строки быть не должно
        Species species = givenSpecies(100L);
        species.setLightPreference(null);
        givenCardFor(species);

        // act
        cardService.showPlantCard(givenUser(1L, 555L), 10L, null, PlantCardService.BACK_TO_LIST, client);

        // assert
        assertThat(cardText()).doesNotContain(LIGHT_PREFIX);
    }

    // ------------------------------------------------------------------ helpers

    private void givenCardFor(Species species) {
        Plant plant = givenPlant(10L, givenUser(1L, 555L), species);
        lenient().when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(plant));
        lenient().when(plantService.getActiveSchedules(10L)).thenReturn(List.of());
    }

    private String cardText() throws Exception {
        var captor = ArgumentCaptor.forClass(SendMessage.class);
        org.mockito.Mockito.verify(client).execute(captor.capture());
        return captor.getValue().getText();
    }

    private User givenUser(Long id, Long chatId) {
        var u = User.builder().telegramChatId(chatId).build();
        setId(u, id);
        return u;
    }

    private Species givenSpecies(Long id) {
        var s = new Species();
        s.setName("Монстера");
        setId(s, id);
        return s;
    }

    private Plant givenPlant(Long id, User user, Species species) {
        var p = new Plant();
        p.setName("Моя монстера");
        p.setUser(user);
        p.setSpecies(species);
        setId(p, id);
        return p;
    }

    private static void setId(Object entity, Long id) {
        try {
            Field f = com.plantcare.core.domain.base.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

package com.plantcare.bot.service;

import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.Species;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.seasonal.service.SeasonalIntervalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты кнопки «🌍 О виде» в детальной карточке растения (ADR-011, issue #129).
 *
 * <p>Тяжёлый Telegram-контекст мокаем целиком (внешний API — {@link TelegramClient}),
 * перехватываем отправленное {@link SendMessage} и инспектируем клавиатуру. Бизнес-
 * коллабораторы (репозиторий, сервисы) подменены моками точечно — нас интересует
 * только условие появления кнопки в зависимости от {@code speciesFactService}.
 */
@ExtendWith(MockitoExtension.class)
class PlantCardSpeciesFactsTest {

    private static final String SPECIES_FACTS_BTN = "🌍 О виде";

    @Mock private PlantService plantService;
    @Mock private PlantRepository plantRepository;
    @Mock private com.plantcare.bot.repository.CareScheduleRepository careScheduleRepository;
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

        // issue #138: карточка дёргает health-score; для фактов вида он не важен.
        lenient().when(healthScoreService.computeForPlant(any()))
                .thenReturn(HealthScoreService.HealthScore.insufficient());
    }

    @Test
    void should_show_species_facts_button_when_species_has_facts() throws Exception {
        // arrange
        User user = givenUser(1L, 555L);
        Plant plant = givenPlant(10L, user, givenSpecies(100L));
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of());
        when(speciesFactService.hasFactsForSpecies(100L)).thenReturn(true);

        // act
        cardService.showPlantCard(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        // assert
        assertThat(buttonTexts(captureKeyboard())).contains(SPECIES_FACTS_BTN);
    }

    @Test
    void should_hide_species_facts_button_when_species_has_no_facts() throws Exception {
        // arrange
        User user = givenUser(1L, 555L);
        Plant plant = givenPlant(10L, user, givenSpecies(100L));
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of());
        when(speciesFactService.hasFactsForSpecies(100L)).thenReturn(false);

        // act
        cardService.showPlantCard(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        // assert
        assertThat(buttonTexts(captureKeyboard())).doesNotContain(SPECIES_FACTS_BTN);
    }

    @Test
    void should_hide_species_facts_button_when_species_is_null() throws Exception {
        // arrange
        User user = givenUser(1L, 555L);
        Plant plant = givenPlant(10L, user, null);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of());

        // act
        cardService.showPlantCard(user, 10L, null, PlantCardService.BACK_TO_LIST, client);

        // assert — без вида сервис фактов даже не дёргается, кнопки нет
        assertThat(buttonTexts(captureKeyboard())).doesNotContain(SPECIES_FACTS_BTN);
    }

    // ------------------------------------------------------------------ helpers

    private InlineKeyboardMarkup captureKeyboard() throws Exception {
        var captor = ArgumentCaptor.forClass(SendMessage.class);
        org.mockito.Mockito.verify(client).execute(captor.capture());
        return (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
    }

    private List<String> buttonTexts(InlineKeyboardMarkup keyboard) {
        return keyboard.getKeyboard().stream()
                .flatMap(List::stream)
                .map(InlineKeyboardButton::getText)
                .toList();
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
            Field f = com.plantcare.bot.domain.base.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

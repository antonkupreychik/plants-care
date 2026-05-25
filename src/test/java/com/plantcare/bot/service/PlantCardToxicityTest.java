package com.plantcare.bot.service;

import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.Species;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.HealthZone;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.seasonal.service.SeasonalIntervalService;
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
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты блока токсичности вида в детальной карточке растения (issue #130).
 *
 * <p>Подход повторяет {@link PlantCardSpeciesFactsTest}: тяжёлый Telegram-API
 * ({@link TelegramClient}) мокаем целиком, перехватываем отправленное
 * {@link SendMessage} и инспектируем его текст. Бизнес-коллабораторы подменены
 * моками точечно — проверяем именно появление/отсутствие бейджей токсичности в
 * тексте карточки в зависимости от тройного состояния флагов вида.
 */
@ExtendWith(MockitoExtension.class)
class PlantCardToxicityTest {

    private static final String CATS_BADGE = "токсично для кошек";
    private static final String DOGS_BADGE = "токсично для собак";
    private static final String HUMANS_BADGE = "токсично для детей";

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

        // issue #138: карточка дёргает health-score; для токсичности он не важен —
        // возвращаем «мало данных», чтобы строка балла не мешала ассертам.
        lenient().when(healthScoreService.computeForPlant(any()))
                .thenReturn(HealthScoreService.HealthScore.insufficient());
    }

    @Test
    void should_show_cats_badge_when_species_toxic_to_cats() throws Exception {
        // arrange
        Species species = givenSpecies(100L);
        species.setToxicToCats(true);
        givenCardFor(species);

        // act
        cardService.showPlantCard(givenUser(1L, 555L), 10L, null, PlantCardService.BACK_TO_LIST, client);

        // assert
        assertThat(cardText()).contains(CATS_BADGE);
    }

    @Test
    void should_show_cats_and_dogs_badges_but_not_humans_when_cats_and_dogs_true_and_humans_null()
            throws Exception {
        // arrange
        Species species = givenSpecies(100L);
        species.setToxicToCats(true);
        species.setToxicToDogs(true);
        species.setToxicToHumans(null);
        givenCardFor(species);

        // act
        cardService.showPlantCard(givenUser(1L, 555L), 10L, null, PlantCardService.BACK_TO_LIST, client);

        // assert
        String text = cardText();
        assertThat(text).contains(CATS_BADGE);
        assertThat(text).contains(DOGS_BADGE);
        assertThat(text).doesNotContain(HUMANS_BADGE);
    }

    @Test
    void should_render_humans_flag_as_children_label_when_toxic_to_humans() throws Exception {
        // arrange
        Species species = givenSpecies(100L);
        species.setToxicToHumans(true);
        givenCardFor(species);

        // act
        cardService.showPlantCard(givenUser(1L, 555L), 10L, null, PlantCardService.BACK_TO_LIST, client);

        // assert — именно «для детей», а не «для людей»
        String text = cardText();
        assertThat(text).contains(HUMANS_BADGE);
        assertThat(text).doesNotContain("токсично для людей");
    }

    @Test
    void should_not_show_cats_badge_when_toxic_to_cats_is_false() throws Exception {
        // arrange — false (а не null): известно, что безопасно, бейдж не нужен
        Species species = givenSpecies(100L);
        species.setToxicToCats(false);
        givenCardFor(species);

        // act
        cardService.showPlantCard(givenUser(1L, 555L), 10L, null, PlantCardService.BACK_TO_LIST, client);

        // assert
        assertThat(cardText()).doesNotContain(CATS_BADGE);
    }

    @Test
    void should_not_show_cats_badge_when_toxic_to_cats_is_null() throws Exception {
        // arrange — null: данных нет, бейдж не показываем (отдельно от false)
        Species species = givenSpecies(100L);
        species.setToxicToCats(null);
        givenCardFor(species);

        // act
        cardService.showPlantCard(givenUser(1L, 555L), 10L, null, PlantCardService.BACK_TO_LIST, client);

        // assert
        assertThat(cardText()).doesNotContain(CATS_BADGE);
    }

    @Test
    void should_hide_whole_toxicity_block_when_all_flags_are_null() throws Exception {
        // arrange — все три null: блок отсутствует целиком
        Species species = givenSpecies(100L);
        species.setToxicToCats(null);
        species.setToxicToDogs(null);
        species.setToxicToHumans(null);
        givenCardFor(species);

        // act
        cardService.showPlantCard(givenUser(1L, 555L), 10L, null, PlantCardService.BACK_TO_LIST, client);

        // assert
        String text = cardText();
        assertThat(text).doesNotContain(CATS_BADGE);
        assertThat(text).doesNotContain(DOGS_BADGE);
        assertThat(text).doesNotContain(HUMANS_BADGE);
        assertThat(text).doesNotContain("токсично");
    }

    @Test
    void should_hide_whole_toxicity_block_when_all_flags_are_false() throws Exception {
        // arrange — все три false: вид безопасен, блок отсутствует целиком
        Species species = givenSpecies(100L);
        species.setToxicToCats(false);
        species.setToxicToDogs(false);
        species.setToxicToHumans(false);
        givenCardFor(species);

        // act
        cardService.showPlantCard(givenUser(1L, 555L), 10L, null, PlantCardService.BACK_TO_LIST, client);

        // assert
        String text = cardText();
        assertThat(text).doesNotContain(CATS_BADGE);
        assertThat(text).doesNotContain(DOGS_BADGE);
        assertThat(text).doesNotContain(HUMANS_BADGE);
        assertThat(text).doesNotContain("токсично");
    }

    // ------------------------------------------------------ health-score (issue #138)

    @Test
    void should_render_health_score_line_with_value_and_zone_emoji_when_score_computed()
            throws Exception {
        // arrange — реальный балл в зелёной зоне; здесь стаб health-score
        // перекрывает дефолтный insufficient() из setUp
        Species species = givenSpecies(100L);
        givenCardFor(species);
        when(healthScoreService.computeForPlant(any()))
                .thenReturn(HealthScoreService.HealthScore.of(82, HealthZone.GREEN));

        // act
        cardService.showPlantCard(givenUser(1L, 555L), 10L, null, PlantCardService.BACK_TO_LIST, client);

        // assert
        String text = cardText();
        assertThat(text).contains("❤️ Health: 82 🟢");
        assertThat(text).doesNotContain("Пока мало данных");
    }

    @Test
    void should_render_insufficient_data_line_when_health_score_has_not_enough_data()
            throws Exception {
        // arrange — мок по умолчанию из setUp возвращает insufficient()
        Species species = givenSpecies(100L);
        givenCardFor(species);

        // act
        cardService.showPlantCard(givenUser(1L, 555L), 10L, null, PlantCardService.BACK_TO_LIST, client);

        // assert
        String text = cardText();
        assertThat(text).contains("❤️ Health: Пока мало данных");
        assertThat(text).doesNotContain("🟢");
        assertThat(text).doesNotContain("🟡");
        assertThat(text).doesNotContain("🔴");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Готовит карточку для растения с заданным видом: стабит загрузку растения и
     * пустой список расписаний. {@code hasFactsForSpecies} осознанно не стабим —
     * мок по умолчанию вернёт {@code false}, нас интересует только текст блока.
     */
    private void givenCardFor(Species species) {
        Plant plant = givenPlant(10L, givenUser(1L, 555L), species);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L)).thenReturn(Optional.of(plant));
        when(plantService.getActiveSchedules(10L)).thenReturn(List.of());
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
            Field f = com.plantcare.bot.domain.base.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

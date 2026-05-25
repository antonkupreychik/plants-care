package com.plantcare.bot.service;

import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.PlantEvent;
import com.plantcare.bot.domain.ShoppingItem;
import com.plantcare.bot.domain.TransplantSupplySuggestion;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.PlantEventType;
import com.plantcare.bot.domain.enums.SuggestionStatus;
import com.plantcare.bot.repository.LocationRepository;
import com.plantcare.bot.repository.PlantEventRepository;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.repository.ShoppingItemRepository;
import com.plantcare.bot.repository.TransplantSupplySuggestionRepository;
import com.plantcare.bot.repository.UserRepository;
import com.plantcare.bot.service.TransplantSuggestionService.DueSuggestion;
import com.plantcare.bot.service.TransplantSuggestionService.ReactionResult;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты {@link TransplantSuggestionService} (issue #141) на реальном
 * Postgres через Testcontainers. Репозитории не мокаются — проверяем фактический
 * расчёт горизонта в TZ юзера, идемпотентность создания (UNIQUE) и идемпотентность
 * реакций на кнопки с записью в shopping_items (issue #136).
 *
 * <p>«Предстоящая пересадка» = дата последней TRANSPLANT-записи + intervalMonths
 * (дефолт 12). Окно «скоро» = predicted-дата в [today; today + horizonDays] (дефолт 14)
 * в TZ юзера. Конфиг берётся из application.yml (профиль test не переопределяет фичу).
 */
@DisplayName("TransplantSuggestionService — подсказка расходников перед пересадкой (issue #141)")
class TransplantSuggestionServiceTest extends IntegrationTestBase {

    @Autowired private TransplantSuggestionService service;

    @Autowired private UserRepository userRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private PlantRepository plantRepository;
    @Autowired private PlantEventRepository plantEventRepository;
    @Autowired private TransplantSupplySuggestionRepository suggestionRepository;
    @Autowired private ShoppingItemRepository shoppingItemRepository;

    @AfterEach
    void cleanup() {
        suggestionRepository.deleteAll();
        shoppingItemRepository.deleteAll();
        plantEventRepository.deleteAll();
        plantRepository.deleteAll();
        locationRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ===================== AC: подсказка появляется в горизонте =====================

    @Test
    @DisplayName("should_return_due_suggestion_when_predicted_transplant_within_horizon")
    void should_return_due_suggestion_when_predicted_transplant_within_horizon() {
        // arrange: пересадка год назад минус неделю → predicted = через ~7 дней,
        // что внутри окна 14 дней.
        User user = savedUser(3001L, "UTC");
        Plant plant = savedPlant(user, "Фикус");
        // now = 2026-05-25 09:00Z; last transplant = 2025-05-18 → +12мес = 2026-05-18?
        // Нет: хотим predicted внутри [today; today+14]. today=2026-05-25.
        // predicted = 2026-06-01 (через 7 дней) → last = 2025-06-01.
        PlantEvent transplant = savedTransplant(plant, LocalDateTime.of(2025, 6, 1, 12, 0));

        Instant now = Instant.parse("2026-05-25T09:00:00Z");

        // act
        List<DueSuggestion> due = service.findDueSuggestions(now);

        // assert
        assertThat(due).hasSize(1);
        DueSuggestion s = due.get(0);
        assertThat(s.plantId()).isEqualTo(plant.getId());
        assertThat(s.plantName()).isEqualTo("Фикус");
        assertThat(s.sourceEventId()).isEqualTo(transplant.getId());
        assertThat(s.userId()).isEqualTo(user.getId());
        assertThat(s.telegramChatId()).isEqualTo(3001L);
        assertThat(s.predictedTransplantAt()).isEqualTo(LocalDateTime.of(2026, 6, 1, 12, 0));
    }

    @Test
    @DisplayName("should_return_due_suggestion_when_predicted_is_exactly_today")
    void should_return_due_suggestion_when_predicted_is_exactly_today() {
        // arrange: граница «слева» — predicted ровно сегодня, должно попасть.
        User user = savedUser(3002L, "UTC");
        Plant plant = savedPlant(user, "Монстера");
        savedTransplant(plant, LocalDateTime.of(2025, 5, 25, 8, 0));

        Instant now = Instant.parse("2026-05-25T09:00:00Z");

        // act
        List<DueSuggestion> due = service.findDueSuggestions(now);

        // assert
        assertThat(due).hasSize(1);
        assertThat(due.get(0).plantId()).isEqualTo(plant.getId());
    }

    @Test
    @DisplayName("should_return_due_suggestion_when_predicted_on_last_day_of_horizon")
    void should_return_due_suggestion_when_predicted_on_last_day_of_horizon() {
        // arrange: граница «справа» — predicted ровно today+14, должно попасть.
        User user = savedUser(3003L, "UTC");
        Plant plant = savedPlant(user, "Замиокулькас");
        // today=2026-05-25, horizon=14 → windowEnd=2026-06-08. predicted=2026-06-08.
        savedTransplant(plant, LocalDateTime.of(2025, 6, 8, 12, 0));

        Instant now = Instant.parse("2026-05-25T09:00:00Z");

        // act
        List<DueSuggestion> due = service.findDueSuggestions(now);

        // assert
        assertThat(due).hasSize(1);
        assertThat(due.get(0).plantId()).isEqualTo(plant.getId());
    }

    // ===================== AC: НЕ появляется вне горизонта / без истории =====================

    @Test
    @DisplayName("should_not_return_suggestion_when_predicted_beyond_horizon")
    void should_not_return_suggestion_when_predicted_beyond_horizon() {
        // arrange: predicted = today+15 (на день дальше окна 14) → не подсказываем.
        User user = savedUser(3004L, "UTC");
        Plant plant = savedPlant(user, "Кактус");
        savedTransplant(plant, LocalDateTime.of(2025, 6, 9, 12, 0));

        Instant now = Instant.parse("2026-05-25T09:00:00Z");

        // act
        List<DueSuggestion> due = service.findDueSuggestions(now);

        // assert
        assertThat(due).isEmpty();
    }

    @Test
    @DisplayName("should_not_return_suggestion_when_predicted_is_in_the_past")
    void should_not_return_suggestion_when_predicted_is_in_the_past() {
        // arrange: predicted = вчера → пропущенная пересадка, не наша забота здесь.
        User user = savedUser(3005L, "UTC");
        Plant plant = savedPlant(user, "Просрочка");
        savedTransplant(plant, LocalDateTime.of(2025, 5, 24, 12, 0));

        Instant now = Instant.parse("2026-05-25T09:00:00Z");

        // act
        List<DueSuggestion> due = service.findDueSuggestions(now);

        // assert
        assertThat(due).isEmpty();
    }

    @Test
    @DisplayName("should_not_return_suggestion_when_plant_has_no_transplant_history")
    void should_not_return_suggestion_when_plant_has_no_transplant_history() {
        // arrange: растение без единой TRANSPLANT-записи (есть другое событие).
        User user = savedUser(3006L, "UTC");
        Plant plant = savedPlant(user, "Без пересадок");
        savedEvent(plant, PlantEventType.PRUNING, LocalDateTime.of(2025, 6, 1, 12, 0));

        Instant now = Instant.parse("2026-05-25T09:00:00Z");

        // act
        List<DueSuggestion> due = service.findDueSuggestions(now);

        // assert
        assertThat(due).isEmpty();
    }

    @Test
    @DisplayName("should_not_return_suggestion_when_plant_is_archived")
    void should_not_return_suggestion_when_plant_is_archived() {
        // arrange: пересадка в горизонте, но растение в архиве → исключаем.
        User user = savedUser(3007L, "UTC");
        Plant plant = savedPlant(user, "Архивный фикус");
        savedTransplant(plant, LocalDateTime.of(2025, 6, 1, 12, 0));
        plant.setArchivedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        plantRepository.save(plant);

        Instant now = Instant.parse("2026-05-25T09:00:00Z");

        // act
        List<DueSuggestion> due = service.findDueSuggestions(now);

        // assert
        assertThat(due).isEmpty();
    }

    @Test
    @DisplayName("should_use_latest_transplant_when_plant_has_multiple")
    void should_use_latest_transplant_when_plant_has_multiple() {
        // arrange: две пересадки. Старая дала бы predicted в прошлом, свежая —
        // в горизонте. Сервис обязан брать последнюю по event_date.
        User user = savedUser(3008L, "UTC");
        Plant plant = savedPlant(user, "Часто пересаживаемый");
        savedTransplant(plant, LocalDateTime.of(2024, 1, 1, 12, 0));
        PlantEvent latest = savedTransplant(plant, LocalDateTime.of(2025, 6, 1, 12, 0));

        Instant now = Instant.parse("2026-05-25T09:00:00Z");

        // act
        List<DueSuggestion> due = service.findDueSuggestions(now);

        // assert
        assertThat(due).hasSize(1);
        assertThat(due.get(0).sourceEventId()).isEqualTo(latest.getId());
    }

    @Test
    @DisplayName("should_return_single_suggestion_with_latest_id_when_two_transplants_share_event_date")
    void should_return_single_suggestion_with_latest_id_when_two_transplants_share_event_date() {
        // arrange: две TRANSPLANT-записи с ИДЕНТИЧНЫМ eventDate (минута в минуту).
        // predicted = eventDate + 12мес = 2026-06-01 → внутри окна [2026-05-25; 2026-06-08].
        // Без тай-брейка по id запрос вернул бы обе записи → дубль-подсказка.
        // С тай-брейком — ровно одна, последняя (с большим id).
        User user = savedUser(3022L, "UTC");
        Plant plant = savedPlant(user, "Близнецы по дате");
        LocalDateTime sameDate = LocalDateTime.of(2025, 6, 1, 12, 0);
        PlantEvent earlier = savedTransplant(plant, sameDate);
        PlantEvent later = savedTransplant(plant, sameDate);

        // sanity: даты идентичны, id растут — later строго позже по id
        assertThat(later.getEventDate()).isEqualTo(earlier.getEventDate());
        assertThat(later.getId()).isGreaterThan(earlier.getId());

        Instant now = Instant.parse("2026-05-25T09:00:00Z");

        // act
        List<DueSuggestion> due = service.findDueSuggestions(now);

        // assert: ровно одна подсказка, source — запись с большим id
        assertThat(due).hasSize(1);
        DueSuggestion s = due.get(0);
        assertThat(s.plantId()).isEqualTo(plant.getId());
        assertThat(s.sourceEventId()).isEqualTo(later.getId());
    }

    @Test
    @DisplayName("should_not_return_suggestion_when_user_is_blocked")
    void should_not_return_suggestion_when_user_is_blocked() {
        // arrange
        User user = savedUser(3009L, "UTC");
        user.setBlocked(true);
        userRepository.save(user);
        Plant plant = savedPlant(user, "Заблокированный юзер");
        savedTransplant(plant, LocalDateTime.of(2025, 6, 1, 12, 0));

        Instant now = Instant.parse("2026-05-25T09:00:00Z");

        // act
        List<DueSuggestion> due = service.findDueSuggestions(now);

        // assert
        assertThat(due).isEmpty();
    }

    // ===================== AC: граница таймзоны (Asia/Almaty, не-UTC) =====================

    @Test
    @DisplayName("should_return_suggestion_in_almaty_zone_when_utc_date_is_still_before_horizon")
    void should_return_suggestion_in_almaty_zone_when_utc_date_is_still_before_horizon() {
        // arrange: юзер в Asia/Almaty (UTC+5). now = 2026-05-25 20:00Z, в Алматы это
        // уже 2026-05-26 01:00 → today(Almaty)=2026-05-26, windowEnd=2026-06-09.
        // В UTC же today=2026-05-25, windowEnd=2026-06-08.
        // predicted = 2026-06-09 12:00Z → локальная дата в Almaty = 2026-06-09 (= windowEnd,
        // попадает), а в UTC это 2026-06-09 (за окном 2026-06-08, НЕ попало бы).
        // Тест доказывает, что расчёт ведётся в TZ юзера, а не в UTC.
        User user = savedUser(3010L, "Asia/Almaty");
        Plant plant = savedPlant(user, "Алматинский фикус");
        PlantEvent transplant = savedTransplant(plant, LocalDateTime.of(2025, 6, 9, 12, 0));

        Instant now = Instant.parse("2026-05-25T20:00:00Z");

        // sanity: now «завтра» в Алматы и «сегодня» в UTC
        assertThat(now.atZone(ZoneOffset.UTC).toLocalDate()).isEqualTo(LocalDate.of(2026, 5, 25));
        assertThat(now.atZone(ZoneId.of("Asia/Almaty")).toLocalDate()).isEqualTo(LocalDate.of(2026, 5, 26));

        // act
        List<DueSuggestion> due = service.findDueSuggestions(now);

        // assert: подсказка есть именно из-за расчёта в TZ юзера
        assertThat(due).hasSize(1);
        assertThat(due.get(0).sourceEventId()).isEqualTo(transplant.getId());
    }

    @Test
    @DisplayName("should_not_return_suggestion_in_almaty_zone_when_predicted_already_yesterday_locally")
    void should_not_return_suggestion_in_almaty_zone_when_predicted_already_yesterday_locally() {
        // arrange: зеркальный кейс. now = 2026-05-25 20:00Z → today(Almaty)=2026-05-26.
        // predicted = 2026-05-25 18:00Z → в Almaty это 2026-05-25 23:00 = вчера локально,
        // уже before today(Almaty)=2026-05-26 → НЕ подсказываем.
        // В UTC же predicted-дата (2026-05-25) совпала бы с today(UTC)=2026-05-25 и попала бы.
        User user = savedUser(3011L, "Asia/Almaty");
        Plant plant = savedPlant(user, "Алматинская монстера");
        savedTransplant(plant, LocalDateTime.of(2025, 5, 25, 18, 0));

        Instant now = Instant.parse("2026-05-25T20:00:00Z");

        // act
        List<DueSuggestion> due = service.findDueSuggestions(now);

        // assert
        assertThat(due).isEmpty();
    }

    // ===================== AC: идемпотентность создания (UNIQUE) =====================

    @Test
    @DisplayName("should_return_empty_when_recordSuggested_called_twice_for_same_event")
    void should_return_empty_when_recordSuggested_called_twice_for_same_event() {
        // arrange
        User user = savedUser(3012L, "UTC");
        Plant plant = savedPlant(user, "Идемпотентный");
        savedTransplant(plant, LocalDateTime.of(2025, 6, 1, 12, 0));
        Instant now = Instant.parse("2026-05-25T09:00:00Z");

        DueSuggestion candidate = service.findDueSuggestions(now).get(0);

        // act: первый recordSuggested создаёт строку и возвращает её id.
        var firstId = service.recordSuggested(candidate);

        // Повтор по тому же source_event нарушает UNIQUE. По контракту метод
        // ловит конфликт во вложенной REQUIRES_NEW-транзакции и возвращает
        // Optional.empty() — без выброса TransactionException/UnexpectedRollbackException.
        var secondId = service.recordSuggested(candidate);

        // assert: первый — present, второй — empty; на уровне БД ровно одна строка.
        assertThat(firstId).isPresent();
        assertThat(secondId).isEmpty();
        assertThat(suggestionRepository.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("should_not_offer_already_recorded_suggestion_on_next_tick")
    void should_not_offer_already_recorded_suggestion_on_next_tick() {
        // arrange: эмуляция двух тиков шедулера по одному и тому же source_event.
        User user = savedUser(3013L, "UTC");
        Plant plant = savedPlant(user, "Два тика");
        savedTransplant(plant, LocalDateTime.of(2025, 6, 1, 12, 0));
        Instant now = Instant.parse("2026-05-25T09:00:00Z");

        // tick 1: нашли кандидата и зафиксировали отправку
        List<DueSuggestion> firstTick = service.findDueSuggestions(now);
        assertThat(firstTick).hasSize(1);
        service.recordSuggested(firstTick.get(0));

        // act tick 2: тот же source_event уже отмечен → не возвращается
        List<DueSuggestion> secondTick = service.findDueSuggestions(now);

        // assert
        assertThat(secondTick).isEmpty();
        assertThat(suggestionRepository.count()).isEqualTo(1L);
    }

    // ===================== AC: идемпотентность кнопки ADD / SKIP =====================

    @Test
    @DisplayName("should_add_supplies_and_mark_added_when_add_clicked_first_time")
    void should_add_supplies_and_mark_added_when_add_clicked_first_time() {
        // arrange
        User user = savedUser(3014L, "UTC");
        Plant plant = savedPlant(user, "Покупки");
        Long suggestionId = savedSuggestion(user, plant);

        // act
        ReactionResult result = service.addToShoppingList(user.getId(), suggestionId);

        // assert: статус ADDED, товары (Грунт, Дренаж) добавлены в список покупок
        assertThat(result).isEqualTo(ReactionResult.OK);
        assertThat(reloadStatus(suggestionId)).isEqualTo(SuggestionStatus.ADDED);
        assertThat(shoppingItemRepository.findAllByUserIdOrderByCheckedAscCreatedAtAsc(user.getId()))
                .extracting(ShoppingItem::getTitle)
                .containsExactlyInAnyOrder("Грунт", "Дренаж");
    }

    @Test
    @DisplayName("should_not_duplicate_supplies_when_add_clicked_twice")
    void should_not_duplicate_supplies_when_add_clicked_twice() {
        // arrange
        User user = savedUser(3015L, "UTC");
        Plant plant = savedPlant(user, "Двойной тап");
        Long suggestionId = savedSuggestion(user, plant);

        // act: повторное нажатие — идемпотентный no-op
        ReactionResult first = service.addToShoppingList(user.getId(), suggestionId);
        ReactionResult second = service.addToShoppingList(user.getId(), suggestionId);

        // assert: первое OK, второе ALREADY_HANDLED, товаров по-прежнему ровно 2, статус ADDED
        assertThat(first).isEqualTo(ReactionResult.OK);
        assertThat(second).isEqualTo(ReactionResult.ALREADY_HANDLED);
        assertThat(reloadStatus(suggestionId)).isEqualTo(SuggestionStatus.ADDED);
        assertThat(shoppingItemRepository.findAllByUserIdOrderByCheckedAscCreatedAtAsc(user.getId()))
                .extracting(ShoppingItem::getTitle)
                .containsExactlyInAnyOrder("Грунт", "Дренаж");
    }

    @Test
    @DisplayName("should_mark_dismissed_when_skip_clicked_and_not_add_supplies")
    void should_mark_dismissed_when_skip_clicked_and_not_add_supplies() {
        // arrange
        User user = savedUser(3016L, "UTC");
        Plant plant = savedPlant(user, "Не нужно");
        Long suggestionId = savedSuggestion(user, plant);

        // act
        ReactionResult result = service.dismiss(user.getId(), suggestionId);

        // assert
        assertThat(result).isEqualTo(ReactionResult.OK);
        assertThat(reloadStatus(suggestionId)).isEqualTo(SuggestionStatus.DISMISSED);
        assertThat(shoppingItemRepository.findAllByUserIdOrderByCheckedAscCreatedAtAsc(user.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("should_be_noop_when_dismiss_clicked_twice")
    void should_be_noop_when_dismiss_clicked_twice() {
        // arrange
        User user = savedUser(3017L, "UTC");
        Plant plant = savedPlant(user, "Двойной skip");
        Long suggestionId = savedSuggestion(user, plant);

        // act
        ReactionResult first = service.dismiss(user.getId(), suggestionId);
        ReactionResult second = service.dismiss(user.getId(), suggestionId);

        // assert: статус остаётся DISMISSED, повтор no-op
        assertThat(first).isEqualTo(ReactionResult.OK);
        assertThat(second).isEqualTo(ReactionResult.ALREADY_HANDLED);
        assertThat(reloadStatus(suggestionId)).isEqualTo(SuggestionStatus.DISMISSED);
    }

    @Test
    @DisplayName("should_not_add_supplies_when_add_clicked_after_dismiss")
    void should_not_add_supplies_when_add_clicked_after_dismiss() {
        // arrange: сначала отклонили, потом по запоздавшему callback пришёл ADD.
        User user = savedUser(3018L, "UTC");
        Plant plant = savedPlant(user, "Сначала skip потом add");
        Long suggestionId = savedSuggestion(user, plant);
        service.dismiss(user.getId(), suggestionId);

        // act
        ReactionResult result = service.addToShoppingList(user.getId(), suggestionId);

        // assert: статус не SUGGESTED → no-op, товары не добавлены, статус остаётся DISMISSED
        assertThat(result).isEqualTo(ReactionResult.ALREADY_HANDLED);
        assertThat(reloadStatus(suggestionId)).isEqualTo(SuggestionStatus.DISMISSED);
        assertThat(shoppingItemRepository.findAllByUserIdOrderByCheckedAscCreatedAtAsc(user.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("should_return_not_found_when_suggestion_belongs_to_another_user")
    void should_return_not_found_when_suggestion_belongs_to_another_user() {
        // arrange: подсказка юзера A, по ней дёргает юзер B (подделанный callback_data).
        User owner = savedUser(3019L, "UTC");
        User attacker = savedUser(3020L, "UTC");
        Plant plant = savedPlant(owner, "Чужая подсказка");
        Long suggestionId = savedSuggestion(owner, plant);

        // act
        ReactionResult result = service.addToShoppingList(attacker.getId(), suggestionId);

        // assert: чужую строку не тронули
        assertThat(result).isEqualTo(ReactionResult.NOT_FOUND);
        assertThat(reloadStatus(suggestionId)).isEqualTo(SuggestionStatus.SUGGESTED);
        assertThat(shoppingItemRepository.findAllByUserIdOrderByCheckedAscCreatedAtAsc(attacker.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("should_return_not_found_when_suggestion_does_not_exist")
    void should_return_not_found_when_suggestion_does_not_exist() {
        // arrange
        User user = savedUser(3021L, "UTC");

        // act
        ReactionResult result = service.addToShoppingList(user.getId(), 999_999L);

        // assert
        assertThat(result).isEqualTo(ReactionResult.NOT_FOUND);
    }

    // ===================== helpers =====================

    private User savedUser(Long chatId, String timezone) {
        return userRepository.save(User.builder()
                .telegramChatId(chatId)
                .timezone(timezone)
                .blocked(false)
                .build());
    }

    private Location savedDefaultLocation(User user) {
        return locationRepository.findByUserIdAndDefaultLocationTrue(user.getId())
                .orElseGet(() -> locationRepository.save(Location.builder()
                        .user(user)
                        .name(Location.DEFAULT_NAME)
                        .emoji(Location.DEFAULT_EMOJI)
                        .defaultLocation(true)
                        .build()));
    }

    private Plant savedPlant(User user, String name) {
        return plantRepository.save(Plant.builder()
                .user(user)
                .location(savedDefaultLocation(user))
                .name(name)
                .acquiredAt(LocalDate.of(2024, 1, 1))
                .build());
    }

    private PlantEvent savedTransplant(Plant plant, LocalDateTime eventDate) {
        return savedEvent(plant, PlantEventType.TRANSPLANT, eventDate);
    }

    private PlantEvent savedEvent(Plant plant, PlantEventType type, LocalDateTime eventDate) {
        return plantEventRepository.save(PlantEvent.builder()
                .plant(plant)
                .eventType(type)
                .eventDate(eventDate)
                .build());
    }

    /** Создаёт SUGGESTED-подсказку через сервис (как сделал бы шедулер). */
    private Long savedSuggestion(User user, Plant plant) {
        PlantEvent transplant = savedTransplant(plant, LocalDateTime.of(2025, 6, 1, 12, 0));
        DueSuggestion candidate = new DueSuggestion(
                user.getId(),
                user.getTelegramChatId(),
                plant.getId(),
                plant.getName(),
                transplant.getId(),
                LocalDateTime.of(2026, 6, 1, 12, 0)
        );
        return service.recordSuggested(candidate).orElseThrow();
    }

    private SuggestionStatus reloadStatus(Long suggestionId) {
        return suggestionRepository.findById(suggestionId)
                .map(TransplantSupplySuggestion::getStatus)
                .orElseThrow();
    }
}

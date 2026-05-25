package com.plantcare.bot.service;

import com.plantcare.bot.domain.ShoppingItem;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.repository.ShoppingItemRepository;
import com.plantcare.bot.repository.UserRepository;
import com.plantcare.bot.support.IntegrationTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные тесты {@link ShoppingListService} на реальном Postgres (Testcontainers).
 *
 * <p>Репозиторий не мокается — проверяем фактическую персистентность, скоупинг по
 * владельцу, идемпотентность toggle (двойной тап не плодит строки) и порядок выдачи.
 */
class ShoppingListServiceTest extends IntegrationTestBase {

    @Autowired
    private ShoppingListService shoppingListService;

    @Autowired
    private ShoppingItemRepository shoppingItemRepository;

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private User userA;
    private User userB;

    @BeforeEach
    void setUp() {
        userA = userRepository.save(User.builder().telegramChatId(7001L).build());
        userB = userRepository.save(User.builder().telegramChatId(7002L).build());
    }

    @AfterEach
    void cleanup() {
        shoppingItemRepository.deleteAll();
        userRepository.deleteAll();
    }

    // --- AC 1: add happy path ---

    @Test
    void should_persist_unchecked_item_owned_by_user_when_title_valid() {
        ShoppingItem saved = shoppingListService.add(userA, "Грунт для суккулентов");

        assertThat(saved.getId()).isNotNull();

        ShoppingItem reloaded = shoppingItemRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Грунт для суккулентов");
        assertThat(reloaded.isChecked()).isFalse();
        assertThat(reloaded.getUser().getId()).isEqualTo(userA.getId());
    }

    @Test
    void should_trim_surrounding_whitespace_when_adding_title() {
        ShoppingItem saved = shoppingListService.add(userA, "   Керамзит   ");

        ShoppingItem reloaded = shoppingItemRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Керамзит");
    }

    // --- AC 2: add edge - blank/empty ---

    @Test
    void should_reject_when_title_is_whitespace_only() {
        assertThatThrownBy(() -> shoppingListService.add(userA, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не может быть пустым");

        assertThat(shoppingItemRepository.findAllByUserIdOrderByCheckedAscCreatedAtAsc(userA.getId()))
                .isEmpty();
    }

    @Test
    void should_reject_when_title_is_empty() {
        assertThatThrownBy(() -> shoppingListService.add(userA, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не может быть пустым");
    }

    @Test
    void should_reject_when_title_is_null() {
        assertThatThrownBy(() -> shoppingListService.add(userA, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не может быть пустым");
    }

    // --- AC 3: add edge - too long ---

    @Test
    void should_reject_when_title_exceeds_160_chars() {
        String tooLong = "а".repeat(161);

        assertThatThrownBy(() -> shoppingListService.add(userA, tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("160");

        assertThat(shoppingItemRepository.findAllByUserIdOrderByCheckedAscCreatedAtAsc(userA.getId()))
                .isEmpty();
    }

    @Test
    void should_accept_when_title_exactly_160_chars() {
        String exactly160 = "а".repeat(160);

        ShoppingItem saved = shoppingListService.add(userA, exactly160);

        assertThat(saved.getTitle()).hasSize(160);
    }

    // --- AC 4: toggle marks/unmarks ---

    @Test
    void should_mark_item_checked_when_toggle_target_true() {
        ShoppingItem item = shoppingListService.add(userA, "Удобрение");

        shoppingListService.toggle(userA.getId(), item.getId(), true);

        ShoppingItem reloaded = shoppingItemRepository.findById(item.getId()).orElseThrow();
        assertThat(reloaded.isChecked()).isTrue();
    }

    @Test
    void should_unmark_item_when_toggle_target_false() {
        ShoppingItem item = shoppingListService.add(userA, "Удобрение");
        shoppingListService.toggle(userA.getId(), item.getId(), true);

        shoppingListService.toggle(userA.getId(), item.getId(), false);

        ShoppingItem reloaded = shoppingItemRepository.findById(item.getId()).orElseThrow();
        assertThat(reloaded.isChecked()).isFalse();
    }

    @Test
    void should_throw_when_toggling_missing_item() {
        assertThatThrownBy(() -> shoppingListService.toggle(userA.getId(), 999_999L, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не найдена");
    }

    // --- AC 5: toggle idempotency (CRITICAL): double tap = one final state, no duplicate rows ---

    @Test
    void should_keep_one_checked_item_and_no_extra_rows_when_checked_twice() {
        ShoppingItem item = shoppingListService.add(userA, "Горшок");

        shoppingListService.toggle(userA.getId(), item.getId(), true);
        shoppingListService.toggle(userA.getId(), item.getId(), true);

        List<ShoppingItem> items = shoppingItemRepository.findAllByUserIdOrderByCheckedAscCreatedAtAsc(userA.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getId()).isEqualTo(item.getId());
        assertThat(items.get(0).isChecked()).isTrue();
    }

    @Test
    void should_not_change_total_count_when_toggling_same_state_twice() {
        ShoppingItem item = shoppingListService.add(userA, "Лейка");
        long countBefore = shoppingItemRepository.count();

        shoppingListService.toggle(userA.getId(), item.getId(), false);
        shoppingListService.toggle(userA.getId(), item.getId(), false);

        long countAfter = shoppingItemRepository.count();
        assertThat(countAfter).isEqualTo(countBefore);
        assertThat(shoppingItemRepository.findById(item.getId()).orElseThrow().isChecked()).isFalse();
    }

    // --- AC 6: clear checked ---

    @Test
    void should_remove_only_checked_items_and_return_count_when_clearing() {
        ShoppingItem checked1 = shoppingListService.add(userA, "Купленный 1");
        ShoppingItem checked2 = shoppingListService.add(userA, "Купленный 2");
        ShoppingItem unchecked = shoppingListService.add(userA, "Ещё нужен");
        shoppingListService.toggle(userA.getId(), checked1.getId(), true);
        shoppingListService.toggle(userA.getId(), checked2.getId(), true);

        long removed = shoppingListService.clearChecked(userA.getId());

        assertThat(removed).isEqualTo(2);
        List<ShoppingItem> remaining =
                shoppingItemRepository.findAllByUserIdOrderByCheckedAscCreatedAtAsc(userA.getId());
        assertThat(remaining)
                .extracting(ShoppingItem::getId)
                .containsExactly(unchecked.getId());
    }

    @Test
    void should_return_zero_and_remove_nothing_when_no_checked_items() {
        shoppingListService.add(userA, "Ничего не куплено 1");
        shoppingListService.add(userA, "Ничего не куплено 2");

        long removed = shoppingListService.clearChecked(userA.getId());

        assertThat(removed).isZero();
        assertThat(shoppingItemRepository.findAllByUserIdOrderByCheckedAscCreatedAtAsc(userA.getId()))
                .hasSize(2);
    }

    // --- AC 7: empty list ---

    @Test
    void should_return_empty_list_when_user_has_no_items() {
        List<ShoppingItem> items = shoppingListService.list(userA.getId());

        assertThat(items).isEmpty();
    }

    @Test
    void should_return_empty_list_when_all_items_were_cleared() {
        ShoppingItem item = shoppingListService.add(userA, "Единственная позиция");
        shoppingListService.toggle(userA.getId(), item.getId(), true);
        shoppingListService.clearChecked(userA.getId());

        List<ShoppingItem> items = shoppingListService.list(userA.getId());

        assertThat(items).isEmpty();
    }

    // --- AC 8: user isolation ---

    @Test
    void should_not_return_other_users_items_in_list() {
        shoppingListService.add(userA, "Позиция A");
        shoppingListService.add(userB, "Позиция B");

        List<ShoppingItem> itemsOfA = shoppingListService.list(userA.getId());

        assertThat(itemsOfA)
                .extracting(ShoppingItem::getTitle)
                .containsExactly("Позиция A");
    }

    @Test
    void should_not_toggle_item_belonging_to_another_user() {
        ShoppingItem itemOfA = shoppingListService.add(userA, "Позиция A");

        assertThatThrownBy(() -> shoppingListService.toggle(userB.getId(), itemOfA.getId(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не найдена");

        assertThat(shoppingItemRepository.findById(itemOfA.getId()).orElseThrow().isChecked()).isFalse();
    }

    @Test
    void should_not_clear_checked_items_of_another_user() {
        ShoppingItem itemOfA = shoppingListService.add(userA, "Позиция A");
        shoppingListService.toggle(userA.getId(), itemOfA.getId(), true);

        long removed = shoppingListService.clearChecked(userB.getId());

        assertThat(removed).isZero();
        assertThat(shoppingItemRepository.findById(itemOfA.getId())).isPresent();
    }

    // --- AC 9: ordering (unchecked before checked, then by creation order) ---

    @Test
    @Transactional
    void should_order_unchecked_before_checked_then_by_creation_order() {
        ShoppingItem first = shoppingListService.add(userA, "Первая");
        ShoppingItem second = shoppingListService.add(userA, "Вторая");
        ShoppingItem third = shoppingListService.add(userA, "Третья");
        // created_at проставляется @CreationTimestamp на insert и может совпасть в один тик —
        // задаём детерминированные значения нативным UPDATE (поле updatable=false для JPA),
        // чтобы tie-break по created_at был воспроизводимым без Thread.sleep.
        LocalDateTime base = LocalDateTime.of(2026, 5, 25, 10, 0, 0);
        setCreatedAt(first.getId(), base.minusMinutes(3));
        setCreatedAt(second.getId(), base.minusMinutes(2));
        setCreatedAt(third.getId(), base.minusMinutes(1));

        // first становится checked → должна уехать в конец, несмотря на самое раннее создание
        shoppingListService.toggle(userA.getId(), first.getId(), true);
        entityManager.flush();
        entityManager.clear();

        List<ShoppingItem> items = shoppingListService.list(userA.getId());

        assertThat(items)
                .extracting(ShoppingItem::getTitle)
                .containsExactly("Вторая", "Третья", "Первая");
    }

    private void setCreatedAt(Long itemId, LocalDateTime createdAt) {
        entityManager.createNativeQuery("UPDATE shopping_items SET created_at = :createdAt WHERE id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", itemId)
                .executeUpdate();
    }
}

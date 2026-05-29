package com.plantcare.core.service;

import com.plantcare.core.domain.ShoppingItem;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.ShoppingItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Персональный список покупок расходников (issue #136).
 *
 * <p>Toggle принимает явный целевой статус (а не «инвертируй текущий»), чтобы
 * повторно доставленный/двойной callback приводил к одному и тому же конечному
 * состоянию без создания дублей.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShoppingListService {

    private static final int MAX_TITLE_LENGTH = 160;
    public static final String ITEM_NOT_FOUND = "Позиция не найдена";

    private final ShoppingItemRepository shoppingItemRepository;

    @Transactional(readOnly = true)
    public List<ShoppingItem> list(Long userId) {
        return shoppingItemRepository.findAllByUserIdOrderByCheckedAscCreatedAtAsc(userId);
    }

    @Transactional
    public ShoppingItem add(User user, String rawTitle) {
        String title = normalizeTitle(rawTitle);

        ShoppingItem saved = shoppingItemRepository.save(new ShoppingItem(user, title));

        log.info("Added shopping item {} for user {}", saved.getId(), user.getTelegramChatId());

        return saved;
    }

    /**
     * Частичное обновление позиции, скоупленной по владельцу. Передаются только
     * меняемые поля: {@code checked} и/или {@code title} (null = «не трогать»).
     * Возвращает обновлённую сущность.
     */
    @Transactional
    public ShoppingItem update(Long userId, Long itemId, Boolean checked, String title) {
        // Валидируем title ДО поиска, чтобы битый ввод всегда давал 400 (а не 404),
        // независимо от существования позиции.
        String normalizedTitle = title == null ? null : normalizeTitle(title);

        ShoppingItem item = shoppingItemRepository.findByUserIdAndId(userId, itemId)
                .orElseThrow(() -> new IllegalArgumentException(ITEM_NOT_FOUND));

        if (checked != null) {
            item.setChecked(checked);
        }

        if (normalizedTitle != null) {
            item.setTitle(normalizedTitle);
        }

        log.info("Updated shopping item {} of user {}", itemId, userId);

        return item;
    }

    /**
     * Удаляет одну позицию пользователя. Чужая или несуществующая позиция —
     * IllegalArgumentException (маппится в 404 на REST-слое).
     */
    @Transactional
    public void delete(Long userId, Long itemId) {
        ShoppingItem item = shoppingItemRepository.findByUserIdAndId(userId, itemId)
                .orElseThrow(() -> new IllegalArgumentException(ITEM_NOT_FOUND));

        shoppingItemRepository.delete(item);

        log.info("Deleted shopping item {} of user {}", itemId, userId);
    }

    @Transactional
    public void toggle(Long userId, Long itemId, boolean targetChecked) {
        ShoppingItem item = shoppingItemRepository.findByUserIdAndId(userId, itemId)
                .orElseThrow(() -> new IllegalArgumentException(ITEM_NOT_FOUND));

        item.setChecked(targetChecked);

        log.info("Set shopping item {} of user {} checked={}", itemId, userId, targetChecked);
    }

    @Transactional
    public long clearChecked(Long userId) {
        long removed = shoppingItemRepository.deleteByUserIdAndCheckedTrue(userId);

        log.info("Cleared {} checked shopping items for user {}", removed, userId);

        return removed;
    }

    private static String normalizeTitle(String rawTitle) {
        String title = rawTitle == null ? "" : rawTitle.trim();

        if (title.isBlank()) {
            throw new IllegalArgumentException("Текст позиции не может быть пустым");
        }

        if (title.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException(
                    "Слишком длинно — максимум " + MAX_TITLE_LENGTH + " символов"
            );
        }

        return title;
    }
}

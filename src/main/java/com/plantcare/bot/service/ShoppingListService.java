package com.plantcare.bot.service;

import com.plantcare.bot.domain.ShoppingItem;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.repository.ShoppingItemRepository;
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

    private final ShoppingItemRepository shoppingItemRepository;

    @Transactional(readOnly = true)
    public List<ShoppingItem> list(Long userId) {
        return shoppingItemRepository.findAllByUserIdOrderByCheckedAscCreatedAtAsc(userId);
    }

    @Transactional
    public ShoppingItem add(User user, String rawTitle) {
        String title = rawTitle == null ? "" : rawTitle.trim();

        if (title.isBlank()) {
            throw new IllegalArgumentException("Текст позиции не может быть пустым");
        }

        if (title.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException(
                    "Слишком длинно — максимум " + MAX_TITLE_LENGTH + " символов"
            );
        }

        ShoppingItem saved = shoppingItemRepository.save(new ShoppingItem(user, title));

        log.info("Added shopping item {} for user {}", saved.getId(), user.getTelegramChatId());

        return saved;
    }

    @Transactional
    public void toggle(Long userId, Long itemId, boolean targetChecked) {
        ShoppingItem item = shoppingItemRepository.findByUserIdAndId(userId, itemId)
                .orElseThrow(() -> new IllegalArgumentException("Позиция не найдена"));

        item.setChecked(targetChecked);

        log.info("Set shopping item {} of user {} checked={}", itemId, userId, targetChecked);
    }

    @Transactional
    public long clearChecked(Long userId) {
        long removed = shoppingItemRepository.deleteByUserIdAndCheckedTrue(userId);

        log.info("Cleared {} checked shopping items for user {}", removed, userId);

        return removed;
    }
}

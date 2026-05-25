package com.plantcare.bot.repository;

import com.plantcare.bot.domain.ShoppingItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShoppingItemRepository extends JpaRepository<ShoppingItem, Long> {

    /**
     * Список позиций юзера: сначала ещё не купленные (checked=false),
     * затем купленные; внутри группы — по времени создания.
     * Покрывается индексом idx_shopping_items_user_checked.
     */
    List<ShoppingItem> findAllByUserIdOrderByCheckedAscCreatedAtAsc(Long userId);

    /**
     * Позиция, скоупленная по владельцу — чтобы юзер не смог тронуть чужую строку.
     */
    Optional<ShoppingItem> findByUserIdAndId(Long userId, Long id);

    /**
     * Удаляет все отмеченные позиции юзера, возвращает число удалённых строк.
     */
    long deleteByUserIdAndCheckedTrue(Long userId);
}

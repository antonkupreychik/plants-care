package com.plantcare.core.domain;

import com.plantcare.core.domain.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Позиция персонального списка покупок пользователя (issue #136).
 *
 * <p>id и created_at наследуются из {@link BaseEntity}. checked по умолчанию false —
 * новая позиция считается «ещё нужно купить».
 */
@Entity
@Table(name = "shopping_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShoppingItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(nullable = false)
    private boolean checked = false;

    public ShoppingItem(User user, String title) {
        this.user = user;
        this.title = title;
    }

    /**
     * Идемпотентно выставляет статус «куплено». Повторный вызов с тем же
     * значением ничего не меняет — это база идемпотентности callback'ов (issue #136).
     */
    public void setChecked(boolean checked) {
        this.checked = checked;
    }
}

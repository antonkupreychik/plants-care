package com.plantcare.api.v1;

import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.generated.ShoppingApi;
import com.plantcare.api.generated.model.ShoppingItemCreateRequest;
import com.plantcare.api.generated.model.ShoppingItemDto;
import com.plantcare.api.generated.model.ShoppingItemUpdateRequest;
import com.plantcare.api.generated.model.ShoppingListResponse;
import com.plantcare.core.domain.ShoppingItem;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.ShoppingListService;
import com.plantcare.core.service.UserService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;

/**
 * REST API для персонального списка покупок (issue #196).
 *
 * <p>Документация и mapping живут в сгенерированном {@link ShoppingApi} (см. openapi.yaml).
 */
@RestController
@RequiredArgsConstructor
public class ShoppingController implements ShoppingApi {

    private final ShoppingListService shoppingListService;
    private final UserService userService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public ShoppingListResponse listShoppingItems() {
        Long userId = currentUserProvider.currentUserId();
        return new ShoppingListResponse(
                shoppingListService.list(userId).stream()
                        .map(ShoppingController::toDto)
                        .toList()
        );
    }

    @Override
    public ShoppingItemDto createShoppingItem(ShoppingItemCreateRequest request) {
        User user = userService.getByIdOrThrow(currentUserProvider.currentUserId());
        return toDto(shoppingListService.add(user, request.getTitle()));
    }

    @Override
    public ShoppingItemDto updateShoppingItem(Long id, ShoppingItemUpdateRequest request) {
        Long userId = currentUserProvider.currentUserId();
        try {
            ShoppingItem updated = shoppingListService.update(
                    userId, id, request.getChecked(), request.getTitle());
            return toDto(updated);
        } catch (IllegalArgumentException e) {
            // update() валидирует title ДО поиска, поэтому not-found — единственный
            // случай с этим сообщением; его маппим в 404. Ошибки валидации title
            // несут другое сообщение и пробрасываются дальше → 400.
            throw toNotFoundOrRethrow(e, id);
        }
    }

    @Override
    public void clearCheckedShoppingItems(Boolean checked) {
        // OpenAPI-схема гарантирует checked=true (enum [true]),
        // но добавляем явную проверку для защиты от рефлекторного вызова с false.
        if (!Boolean.TRUE.equals(checked)) {
            throw new IllegalArgumentException("Параметр checked должен быть true");
        }
        Long userId = currentUserProvider.currentUserId();
        shoppingListService.clearChecked(userId);
    }

    @Override
    public void deleteShoppingItem(Long id) {
        Long userId = currentUserProvider.currentUserId();
        try {
            shoppingListService.delete(userId, id);
        } catch (IllegalArgumentException e) {
            throw toNotFoundOrRethrow(e, id);
        }
    }

    private static RuntimeException toNotFoundOrRethrow(IllegalArgumentException e, Long id) {
        if (ShoppingListService.ITEM_NOT_FOUND.equals(e.getMessage())) {
            return new EntityNotFoundException("Shopping item not found: " + id);
        }
        return e;
    }

    private static ShoppingItemDto toDto(ShoppingItem item) {
        ShoppingItemDto dto = new ShoppingItemDto()
                .id(item.getId())
                .title(item.getTitle())
                .checked(item.isChecked());
        if (item.getCreatedAt() != null) {
            dto.createdAt(item.getCreatedAt().atOffset(ZoneOffset.UTC));
        }
        return dto;
    }
}

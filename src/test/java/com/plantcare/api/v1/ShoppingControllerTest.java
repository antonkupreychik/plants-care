package com.plantcare.api.v1;

import tools.jackson.databind.ObjectMapper;
import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.ShoppingItem;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.ShoppingListService;
import com.plantcare.core.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} для {@link ShoppingController} — list/create/update/delete,
 * валидация (400), not-found (404) и cross-user-скоупинг (404) для shopping-list API (issue #196).
 *
 * <p>Зеркалит {@link LocationControllerTest}: фильтры выключены, {@link ApiExceptionHandler}
 * импортируется, {@link CurrentUserProvider} застаблен на user id = 1.
 */
@WebMvcTest(ShoppingController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class ShoppingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ShoppingListService shoppingListService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void stubCurrentUser() {
        when(currentUserProvider.currentUserId()).thenReturn(1L);
    }

    // ------------------------------------------------------------------ helpers

    private ShoppingItem mockItem(long id, String title, boolean checked) {
        ShoppingItem item = mock(ShoppingItem.class);
        when(item.getId()).thenReturn(id);
        when(item.getTitle()).thenReturn(title);
        when(item.isChecked()).thenReturn(checked);
        when(item.getCreatedAt()).thenReturn(LocalDateTime.of(2026, 5, 25, 10, 0, 0));
        return item;
    }

    // ------------------------------------------------------------------ GET list

    @Test
    void should_return_items_wrapped_under_items_when_listing() throws Exception {
        // arrange
        ShoppingItem unchecked = mockItem(1L, "Грунт", false);
        ShoppingItem checked = mockItem(2L, "Горшок", true);

        when(shoppingListService.list(1L)).thenReturn(List.of(unchecked, checked));

        // act + assert
        mockMvc.perform(get("/api/v1/shopping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Грунт"))
                .andExpect(jsonPath("$.items[0].checked").value(false))
                .andExpect(jsonPath("$.items[1].id").value(2))
                .andExpect(jsonPath("$.items[1].checked").value(true));
    }

    @Test
    void should_return_empty_items_array_when_user_has_no_items() throws Exception {
        // arrange
        when(shoppingListService.list(1L)).thenReturn(List.of());

        // act + assert
        mockMvc.perform(get("/api/v1/shopping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    // ------------------------------------------------------------------ POST create

    @Test
    void should_create_item_and_return_201_when_title_valid() throws Exception {
        // arrange
        User user = User.builder().telegramChatId(1L).build();
        ShoppingItem created = mockItem(5L, "Удобрение", false);

        when(userService.getByIdOrThrow(1L)).thenReturn(user);
        when(shoppingListService.add(eq(user), eq("Удобрение"))).thenReturn(created);

        String body = """
                {"title": "Удобрение"}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/shopping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.title").value("Удобрение"))
                .andExpect(jsonPath("$.checked").value(false));
    }

    @Test
    void should_return_400_when_create_title_blank() throws Exception {
        // arrange — @Size(min = 1) on ShoppingItemCreateRequest.title rejects ""
        String body = """
                {"title": ""}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/shopping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("title"));
    }

    @Test
    void should_return_400_when_create_title_missing() throws Exception {
        // arrange — @NotNull on title
        String body = "{}";

        // act + assert
        mockMvc.perform(post("/api/v1/shopping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_return_400_when_create_title_exceeds_160_chars() throws Exception {
        // arrange — @Size(max = 160) on title
        String body = "{\"title\": \"" + "a".repeat(161) + "\"}";

        // act + assert
        mockMvc.perform(post("/api/v1/shopping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("title"));
    }

    // ------------------------------------------------------------------ PATCH update

    @Test
    void should_update_checked_only_and_return_200_when_patching() throws Exception {
        // arrange
        ShoppingItem updated = mockItem(3L, "Лейка", true);

        when(shoppingListService.update(eq(1L), eq(3L), eq(true), isNull())).thenReturn(updated);

        String body = """
                {"checked": true}
                """;

        // act + assert
        mockMvc.perform(patch("/api/v1/shopping/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.checked").value(true));
    }

    @Test
    void should_update_title_only_and_return_200_when_patching() throws Exception {
        // arrange
        ShoppingItem updated = mockItem(3L, "Новый текст", false);

        when(shoppingListService.update(eq(1L), eq(3L), isNull(), eq("Новый текст"))).thenReturn(updated);

        String body = """
                {"title": "Новый текст"}
                """;

        // act + assert
        mockMvc.perform(patch("/api/v1/shopping/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Новый текст"));
    }

    @Test
    void should_return_404_when_patching_unknown_id() throws Exception {
        // arrange — service throws not-found IllegalArgumentException → controller maps to 404
        when(shoppingListService.update(eq(1L), eq(999L), eq(true), isNull()))
                .thenThrow(new IllegalArgumentException(ShoppingListService.ITEM_NOT_FOUND));

        String body = """
                {"checked": true}
                """;

        // act + assert
        mockMvc.perform(patch("/api/v1/shopping/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void should_return_400_when_patch_title_blank() throws Exception {
        // arrange — blank title passes @Size(max=160) bean validation, service rejects with
        // a non-not-found IllegalArgumentException → controller rethrows → 400 BAD_REQUEST.
        when(shoppingListService.update(eq(1L), eq(3L), isNull(), eq("   ")))
                .thenThrow(new IllegalArgumentException("Текст позиции не может быть пустым"));

        String body = """
                {"title": "   "}
                """;

        // act + assert
        mockMvc.perform(patch("/api/v1/shopping/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void should_return_400_when_patch_title_exceeds_160_chars() throws Exception {
        // arrange — @Size(max = 160) on update title rejects via bean validation
        String body = "{\"title\": \"" + "a".repeat(161) + "\"}";

        // act + assert
        mockMvc.perform(patch("/api/v1/shopping/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("title"));
    }

    @Test
    void should_return_404_when_patching_item_of_other_user() throws Exception {
        // arrange — update() is userId-scoped; another user's item is "not found" for user 1
        when(shoppingListService.update(eq(1L), eq(42L), eq(true), isNull()))
                .thenThrow(new IllegalArgumentException(ShoppingListService.ITEM_NOT_FOUND));

        String body = """
                {"checked": true}
                """;

        // act + assert
        mockMvc.perform(patch("/api/v1/shopping/42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ------------------------------------------------------------------ DELETE

    @Test
    void should_delete_item_and_return_204() throws Exception {
        // arrange
        doNothing().when(shoppingListService).delete(1L, 3L);

        // act + assert
        mockMvc.perform(delete("/api/v1/shopping/3"))
                .andExpect(status().isNoContent());

        verify(shoppingListService).delete(1L, 3L);
    }

    @Test
    void should_return_404_when_deleting_unknown_id() throws Exception {
        // arrange — service throws not-found → controller maps to 404
        doThrow(new IllegalArgumentException(ShoppingListService.ITEM_NOT_FOUND))
                .when(shoppingListService).delete(1L, 999L);

        // act + assert
        mockMvc.perform(delete("/api/v1/shopping/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void should_return_404_when_deleting_item_of_other_user() throws Exception {
        // arrange — delete() is userId-scoped; another user's item is "not found" for user 1
        doThrow(new IllegalArgumentException(ShoppingListService.ITEM_NOT_FOUND))
                .when(shoppingListService).delete(1L, 42L);

        // act + assert
        mockMvc.perform(delete("/api/v1/shopping/42"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}

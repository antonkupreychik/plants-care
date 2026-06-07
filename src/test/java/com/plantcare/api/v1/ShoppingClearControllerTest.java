package com.plantcare.api.v1;

import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.service.ShoppingListService;
import com.plantcare.core.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} для {@link ShoppingController#clearCheckedShoppingItems} (issue #257).
 *
 * <p>REST-parity gap #4: bulk-удаление отмеченных позиций списка покупок.
 * Раньше только бот мог очищать отмеченные позиции через {@code ShoppingListService.clearChecked}.
 */
@WebMvcTest(ShoppingController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class ShoppingClearControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShoppingListService shoppingListService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void setUp() {
        when(currentUserProvider.currentUserId()).thenReturn(1L);
    }

    @Test
    void should_return_204_when_checked_items_cleared() throws Exception {
        // arrange
        when(shoppingListService.clearChecked(1L)).thenReturn(3L);

        // act + assert
        mockMvc.perform(delete("/api/v1/shopping/clear")
                        .param("checked", "true"))
                .andExpect(status().isNoContent());

        verify(shoppingListService).clearChecked(1L);
    }

    @Test
    void should_return_204_when_no_checked_items_idempotent() throws Exception {
        // arrange — clearChecked() возвращает 0 (нечего удалять), идемпотентен
        when(shoppingListService.clearChecked(1L)).thenReturn(0L);

        // act + assert
        mockMvc.perform(delete("/api/v1/shopping/clear")
                        .param("checked", "true"))
                .andExpect(status().isNoContent());
    }

    @Test
    void should_return_400_when_checked_param_missing() throws Exception {
        // arrange — checked обязателен (required=true в OpenAPI-схеме)
        mockMvc.perform(delete("/api/v1/shopping/clear"))
                .andExpect(status().isBadRequest());
    }
}

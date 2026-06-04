package com.plantcare.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Web-слайс тесты {@link AuthVerifyController}.
 *
 * <p>Issue #215: веб-фоллбэк для magic-link — проверяем 200 OK и корректный view-name.
 *
 * <p>{@code addFilters = false} — тестируем только контроллер, не security-цепочку.
 */
@WebMvcTest(controllers = AuthVerifyController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("Web-слайс AuthVerifyController — /auth/verify fallback")
class AuthVerifyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("200 OK без query-параметров — страница должна отдаваться даже без токена")
    void should_return_200_when_no_query_params() throws Exception {
        mockMvc.perform(get("/auth/verify"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/verify"));
    }

    @Test
    @DisplayName("200 OK с query-параметром token — токен не обрабатывается, страница отдаётся")
    void should_return_200_with_token_query_param() throws Exception {
        mockMvc.perform(get("/auth/verify").queryParam("token", "some-opaque-token-value"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/verify"));
    }
}

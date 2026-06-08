package com.plantcare.api.v1;

import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.auth.exception.TelegramAuthException;
import com.plantcare.api.auth.ratelimit.TelegramAuthRateLimiter;
import com.plantcare.api.auth.service.TelegramAuthService;
import com.plantcare.api.auth.service.TokenPair;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Слайс-тест {@link TelegramAuthController} (issue #318). Проверяем HTTP-контракт
 * двух эндпоинтов и маппинг кодов ошибок verify через {@link ApiExceptionHandler}.
 */
@WebMvcTest(TelegramAuthController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class TelegramAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TelegramAuthService telegramAuthService;

    @MockitoBean
    private TelegramAuthRateLimiter rateLimiter;

    @Test
    void should_return_session_and_deep_link_on_start() throws Exception {
        when(rateLimiter.isBlocked(anyString())).thenReturn(false);
        when(telegramAuthService.start()).thenReturn(
                new TelegramAuthService.StartResult("sess1", "t.me/bot?start=auth_sess1", 6, 60));

        mockMvc.perform(post("/api/v1/auth/telegram/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sess1"))
                .andExpect(jsonPath("$.deepLink").value("t.me/bot?start=auth_sess1"))
                .andExpect(jsonPath("$.codeLength").value(6))
                .andExpect(jsonPath("$.resendAfterSec").value(60));
    }

    @Test
    void should_start_without_body() throws Exception {
        when(rateLimiter.isBlocked(anyString())).thenReturn(false);
        when(telegramAuthService.start()).thenReturn(
                new TelegramAuthService.StartResult("s", "t.me/bot?start=auth_s", 6, 60));

        mockMvc.perform(post("/api/v1/auth/telegram/start"))
                .andExpect(status().isOk());
    }

    @Test
    void should_return_token_pair_on_successful_verify() throws Exception {
        when(rateLimiter.isBlocked(anyString())).thenReturn(false);
        when(telegramAuthService.verify("sess1", "123456"))
                .thenReturn(new TokenPair("acc", "ref", 3600));

        mockMvc.perform(post("/api/v1/auth/telegram/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"sess1\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("acc"))
                .andExpect(jsonPath("$.refreshToken").value("ref"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void should_return_404_telegram_user_not_found() throws Exception {
        when(rateLimiter.isBlocked(anyString())).thenReturn(false);
        when(telegramAuthService.verify(anyString(), anyString()))
                .thenThrow(TelegramAuthException.userNotFound());

        mockMvc.perform(post("/api/v1/auth/telegram/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s\",\"code\":\"123456\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("telegram_user_not_found"));
    }

    @Test
    void should_return_401_invalid_code() throws Exception {
        when(rateLimiter.isBlocked(anyString())).thenReturn(false);
        when(telegramAuthService.verify(anyString(), anyString()))
                .thenThrow(TelegramAuthException.invalidCode());

        mockMvc.perform(post("/api/v1/auth/telegram/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s\",\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_code"));
    }

    @Test
    void should_return_410_session_expired() throws Exception {
        when(rateLimiter.isBlocked(anyString())).thenReturn(false);
        when(telegramAuthService.verify(anyString(), anyString()))
                .thenThrow(TelegramAuthException.sessionExpired());

        mockMvc.perform(post("/api/v1/auth/telegram/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s\",\"code\":\"123456\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("session_expired"));
    }

    @Test
    void should_return_429_too_many_attempts() throws Exception {
        when(rateLimiter.isBlocked(anyString())).thenReturn(false);
        when(telegramAuthService.verify(anyString(), anyString()))
                .thenThrow(TelegramAuthException.tooManyAttempts());

        mockMvc.perform(post("/api/v1/auth/telegram/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s\",\"code\":\"123456\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("too_many_attempts"));
    }

    @Test
    void should_return_429_when_rate_limited() throws Exception {
        when(rateLimiter.isBlocked(anyString())).thenReturn(true);
        when(rateLimiter.getWindowSeconds()).thenReturn(300L);

        mockMvc.perform(post("/api/v1/auth/telegram/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isTooManyRequests());

        verify(telegramAuthService, never()).start();
    }

    @Test
    void should_reject_verify_with_blank_fields() throws Exception {
        when(rateLimiter.isBlocked(anyString())).thenReturn(false);

        mockMvc.perform(post("/api/v1/auth/telegram/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"\",\"code\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(telegramAuthService, never()).verify(any(), any());
    }
}

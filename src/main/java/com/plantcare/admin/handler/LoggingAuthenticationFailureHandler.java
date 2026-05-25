package com.plantcare.admin.handler;

import com.plantcare.admin.config.AdminSecurityConfig;
import com.plantcare.admin.ratelimit.LoginRateLimiter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Инкрементим rate-limit, логируем (без пароля!), 401 + forward на /admin/login?error
 * чтобы юзер увидел форму с сообщением об ошибке.
 */
@Component
@ConditionalOnExpression(AdminSecurityConfig.ADMIN_ENABLED_EXPR)
@RequiredArgsConstructor
@Slf4j
public class LoggingAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private final LoginRateLimiter rateLimiter;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception)
            throws IOException, ServletException {
        String ip = request.getRemoteAddr();
        String username = request.getParameter("username");
        log.warn("Admin login failed: user={}, ip={}, reason={}",
                username, ip, exception.getClass().getSimpleName());
        rateLimiter.recordFailure(ip);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        request.getRequestDispatcher("/admin/login?error").forward(request, response);
    }
}

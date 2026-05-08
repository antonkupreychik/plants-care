package com.plantcare.bot.admin.handler;

import com.plantcare.bot.admin.config.AdminSecurityConfig;
import com.plantcare.bot.admin.ratelimit.LoginRateLimiter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Логируем (user + IP), сбрасываем rate-limit, редиректим на /admin. */
@Component
@ConditionalOnExpression(AdminSecurityConfig.ADMIN_ENABLED_EXPR)
@RequiredArgsConstructor
@Slf4j
public class LoggingAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final LoginRateLimiter rateLimiter;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication)
            throws IOException, ServletException {
        String ip = request.getRemoteAddr();
        log.info("Admin login successful: user={}, ip={}", authentication.getName(), ip);
        rateLimiter.reset(ip);
        setDefaultTargetUrl("/admin");
        super.onAuthenticationSuccess(request, response, authentication);
    }
}

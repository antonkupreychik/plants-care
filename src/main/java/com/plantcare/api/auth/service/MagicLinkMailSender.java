package com.plantcare.api.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Отправка письма с magic link (issue #88). Вынесена в отдельный компонент,
 * чтобы вызываться ВНЕ транзакции БД (см. {@link MagicLinkService}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MagicLinkMailSender {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.from:no-reply@plantcare.local}")
    private String fromAddress;

    /**
     * Шлёт письмо со ссылкой входа. Исключения отправки не пробрасываем наружу:
     * эндпоинт /email/request всегда отвечает 202 (anti-enumeration), а сбой
     * SMTP не должен раскрывать существование адреса.
     */
    public void send(String toEmail, String magicLinkUrl) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(toEmail);
            message.setSubject("Вход в Plants Care");
            message.setText("""
                    Привет!

                    Чтобы войти в Plants Care, перейди по ссылке ниже. Она действует
                    ограниченное время и работает один раз:

                    %s

                    Если ты не запрашивал вход — просто проигнорируй это письмо.
                    """.formatted(magicLinkUrl));
            mailSender.send(message);
            log.info("Magic link email sent");
        } catch (Exception e) {
            log.error("Failed to send magic link email: {}", e.getMessage(), e);
        }
    }
}

package com.plantcare.core.config;

import com.plantcare.core.service.PushSender;
import lombok.extern.slf4j.Slf4j;

/**
 * No-op реализация {@link PushSender} — используется когда {@code push.enabled=false}
 * (дефолт). Проглатывает все вызовы, логирует на DEBUG.
 *
 * <p>Позволяет запускать приложение и тесты без реальных FCM / APNs-ключей.
 */
@Slf4j
public class NoopPushSender implements PushSender {

    @Override
    public PushResult send(String pushToken, String title, String body) {
        log.debug("Push disabled (push.enabled=false) — skipped: token={}, title={}", pushToken, title);
        return PushResult.SENT;
    }
}

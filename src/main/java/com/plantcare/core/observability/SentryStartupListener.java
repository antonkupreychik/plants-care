package com.plantcare.core.observability;

import io.sentry.Breadcrumb;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Health-чек инициализации Sentry (issue #114).
 *
 * <p>Бин активен только когда {@code sentry.enabled=true} (т.е. на prod). При старте
 * приложения пишет info-breadcrumb «Sentry initialized» и лог-строку, по которой в
 * Railway-логах видно, что DSN подхватился и Sentry-клиент готов отправлять события.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "sentry", name = "enabled", havingValue = "true")
public class SentryStartupListener {

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        boolean enabled = Sentry.isEnabled();

        Breadcrumb breadcrumb = new Breadcrumb();
        breadcrumb.setCategory("startup");
        breadcrumb.setMessage("Sentry initialized");
        breadcrumb.setLevel(SentryLevel.INFO);
        Sentry.addBreadcrumb(breadcrumb);

        // По этой строке в Railway-логах видно, что DSN корректный (Sentry.isEnabled()
        // == true только когда стартер получил непустой DSN и enabled=true).
        log.info("Sentry initialized: enabled={} (breadcrumb 'Sentry initialized' recorded)", enabled);
    }
}

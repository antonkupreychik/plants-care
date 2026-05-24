package com.plantcare.bot.observability;

import io.sentry.Sentry;
import io.sentry.SentryOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Регистрация кастомных опций Sentry поверх автоконфигурации стартера (issue #114).
 *
 * <p>Базовые проперти ({@code sentry.dsn}, {@code sentry.enabled},
 * {@code sentry.environment}, {@code sentry.release}, {@code sentry.send-default-pii})
 * читает сам {@code sentry-spring-boot-starter-jakarta} из {@code application*.yml}.
 * Здесь докручиваем только то, что нельзя выразить пропертями — {@link SentryPiiFilter}
 * как {@code beforeSend}-хук.
 *
 * <p>{@link Sentry.OptionsConfiguration} применяется стартером ко всем экземплярам
 * опций, в т.ч. когда Sentry выключен/no-op — тогда beforeSend просто никогда не
 * вызовется, побочных эффектов нет.
 */
@Configuration
@RequiredArgsConstructor
public class SentryConfig {

    private final SentryPiiFilter sentryPiiFilter;

    @Bean
    public Sentry.OptionsConfiguration<SentryOptions> sentryPiiOptionsConfiguration() {
        return options -> options.setBeforeSend(sentryPiiFilter);
    }
}

package com.plantcare.bot.support;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Optional;

/**
 * Issue #279, эпик #277 фаза 1: в интеграционных тестах ShedLock-лок должен быть
 * прозрачным.
 *
 * <p>Проблема: продакшн-{@code LockProvider} (JDBC поверх Postgres) пишет реальные
 * локи с {@code lockAtLeastFor} в десятки минут. Поскольку Testcontainers-контейнер
 * переиспользуется между тестами, а фоновые {@code @Scheduled}-задачи приложения
 * реально тикают во время жизни тестового контекста, лок в таблице {@code shedlock}
 * остаётся «занятым» и блокирует прямой вызов {@code @SchedulerLock}-метода в тесте —
 * метод молча пропускается, и проверки внешних эффектов («должен отправить push»)
 * падают. Это артефакт тестовой среды, а не дефект продакшн-логики.
 *
 * <p>Решение: в профиле {@code test} подменяем {@code LockProvider} на «всегда даёт
 * лок» — каждый тик в тесте выполняется детерминированно. Распределённую семантику
 * реального провайдера проверяет отдельный {@code ShedLockDistributedLockIT}, который
 * локально переопределяет этот бин на настоящий JDBC-провайдер.
 *
 * <p>Подключается глобально через {@code spring.factories}-механизм импорта в
 * {@link IntegrationTestBase} (см. {@code @Import}).
 */
@TestConfiguration
public class NoOpShedLockTestConfig {

    @Bean
    @Primary
    public LockProvider testLockProvider() {
        return new AlwaysGrantingLockProvider();
    }

    /** Всегда выдаёт лок; {@code unlock()} — no-op. Ничего не персистит. */
    static final class AlwaysGrantingLockProvider implements LockProvider {
        @Override
        public Optional<SimpleLock> lock(LockConfiguration lockConfiguration) {
            return Optional.of(() -> { /* no-op unlock */ });
        }
    }
}

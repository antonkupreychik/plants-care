package com.plantcare.core.shedlock;

import com.plantcare.bot.support.IntegrationTestBase;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #279, эпик #277 фаза 1: интеграционный тест ShedLock JDBC-провайдера.
 *
 * <p>Проверяет AC «два «инстанса» (два потока/контекста) — задача выполняется один раз»:
 * два потока одновременно пытаются взять лок с одним именем; только один должен
 * выполнить «задачу» (инкремент счётчика), второй должен получить пустой Optional.
 *
 * <p>Используется тот же {@link LockProvider} бин, что и в продакшн-конфиге
 * (JdbcTemplateLockProvider поверх реального Postgres).
 */
class ShedLockDistributedLockIT extends IntegrationTestBase {

    /**
     * Базовый {@code IntegrationTestBase} помечает {@code @Primary} no-op
     * {@code LockProvider} (тестовая прозрачность для прямых вызовов
     * {@code @SchedulerLock}-методов в других тестах). Здесь нам нужен НАСТОЯЩИЙ
     * JDBC-провайдер, чтобы проверить именно распределённую взаимоисключающую
     * семантику — инжектим продакшн-бин по имени {@code lockProvider}
     * (см. {@code ShedLockConfig}).
     */
    @Autowired
    @Qualifier("lockProvider")
    private LockProvider lockProvider;

    /**
     * Два потока конкурируют за один лок. Задача (инкремент счётчика) должна
     * выполниться ровно один раз.
     */
    @Test
    void should_execute_task_once_when_two_instances_compete() throws InterruptedException {
        // arrange
        String lockName = "ShedLockDistributedLockIT_concurrent_" + System.currentTimeMillis();
        AtomicInteger executions = new AtomicInteger(0);
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch bothDone = new CountDownLatch(2);

        Runnable taskAttempt = () -> {
            LockConfiguration config = new LockConfiguration(
                    Instant.now(),
                    lockName,
                    Duration.ofSeconds(30),  // lockAtMostFor
                    Duration.ofSeconds(5)    // lockAtLeastFor
            );
            bothStarted.countDown();
            try {
                bothStarted.await(); // синхронизируем старт — оба потока стартуют вместе
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            Optional<SimpleLock> lock = lockProvider.lock(config);
            if (lock.isPresent()) {
                try {
                    // «задача» — внешний эффект, должен случиться один раз
                    executions.incrementAndGet();
                } finally {
                    lock.get().unlock();
                }
            }
            bothDone.countDown();
        };

        Thread instance1 = new Thread(taskAttempt, "instance-1");
        Thread instance2 = new Thread(taskAttempt, "instance-2");

        // act
        instance1.start();
        instance2.start();
        bothDone.await();

        // assert
        assertThat(executions.get())
                .as("Задача должна выполниться ровно один раз при конкурентном запуске двух «инстансов»")
                .isEqualTo(1);
    }

    /**
     * Лок освобождается после unlock(), и следующая попытка с тем же именем
     * должна успешно взять лок (проверка корректности round-trip).
     */
    @Test
    void should_allow_relock_after_unlock() {
        // arrange
        String lockName = "ShedLockDistributedLockIT_relock_" + System.currentTimeMillis();
        LockConfiguration config = new LockConfiguration(
                Instant.now(),
                lockName,
                Duration.ofSeconds(30),
                Duration.ofMillis(1)  // lockAtLeastFor минимальный — чтобы тест не завис
        );

        // act: берём лок, отпускаем, берём снова
        Optional<SimpleLock> firstLock = lockProvider.lock(config);
        assertThat(firstLock).as("Первый захват должен быть успешным").isPresent();
        firstLock.get().unlock();

        // После unlock с lockAtLeastFor=1ms лок должен немедленно освободиться.
        // Небольшая пауза для гарантии применения изменения в БД.
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        LockConfiguration config2 = new LockConfiguration(
                Instant.now(),
                lockName,
                Duration.ofSeconds(30),
                Duration.ofMillis(1)
        );
        Optional<SimpleLock> secondLock = lockProvider.lock(config2);

        // assert
        assertThat(secondLock).as("После unlock второй захват должен быть успешным").isPresent();
        secondLock.get().unlock();
    }
}

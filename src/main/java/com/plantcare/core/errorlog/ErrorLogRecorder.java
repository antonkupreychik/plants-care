package com.plantcare.core.errorlog;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Асинхронный буфер журнала ошибок (issue #97).
 *
 * <p>Между логовым событием и БД стоит ограниченная очередь и один фоновый поток:
 * <ul>
 *   <li>{@link #record(ErrorLogEntry)} вызывается прямо в потоке, который залогировал
 *       ошибку (HTTP-поток, шедулер, Telegram-поллинг). Он делает только
 *       {@code queue.offer()} — неблокирующе. <b>Переполненная очередь роняет событие,
 *       а не тормозит запрос</b>: AC требует «без замедления API», и потерять запись в
 *       журнале диагностики дешевле, чем задержать ответ пользователю;</li>
 *   <li>фоновый одно-поточный планировщик раз в {@code flushInterval} сливает очередь
 *       батч-инсертом.</li>
 * </ul>
 *
 * <p><b>Защита от петли обратной связи.</b> Инсерт сам может залогировать WARN/ERROR
 * (потерян коннект, БД в даунтайме) — и это событие снова попадёт в аппендер. Поэтому на
 * время флаша поднимается {@link #IN_FLUSH}: всё, что логируется внутри флаша, в очередь
 * не попадает. Флаш всегда идёт в отдельном потоке, так что ThreadLocal-флаг покрывает
 * ровно опасный участок и не глушит логи приложения.
 */
@Slf4j
@Service
public class ErrorLogRecorder {

    /**
     * Признак «мы внутри записи журнала». Читается аппендером, чтобы не зациклиться на
     * собственных ошибках записи.
     */
    private static final ThreadLocal<Boolean> IN_FLUSH = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Как часто напоминать в логах о переполнении очереди (в отброшенных событиях). */
    private static final long DROP_LOG_EVERY = 100L;

    private final ErrorLogRepository repository;
    private final ErrorLogProperties properties;
    private final BlockingQueue<ErrorLogEntry> queue;
    private final AtomicLong dropped = new AtomicLong();

    private ScheduledExecutorService flusher;

    public ErrorLogRecorder(ErrorLogRepository repository, ErrorLogProperties properties) {
        this.repository = repository;
        this.properties = properties;
        this.queue = new ArrayBlockingQueue<>(properties.queueCapacity());
    }

    /** {@code true}, если текущий поток занят записью журнала (см. защиту от петли). */
    public static boolean isInsideFlush() {
        return Boolean.TRUE.equals(IN_FLUSH.get());
    }

    @PostConstruct
    void start() {
        long periodMs = Math.max(1L, properties.flushInterval().toMillis());
        flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "error-log-flusher");
            thread.setDaemon(true);
            return thread;
        });
        flusher.scheduleWithFixedDelay(this::flushQuietly, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (flusher != null) {
            flusher.shutdownNow();
        }
        // Последний слив: то, что накопилось за время между тиками, иначе теряется
        // ровно на выключении — а это самый интересный момент для диагностики.
        flushQuietly();
    }

    /**
     * Кладёт запись в очередь. Никогда не блокирует и никогда не бросает.
     *
     * @return {@code true}, если запись принята; {@code false}, если очередь переполнена
     */
    public boolean record(ErrorLogEntry entry) {
        if (entry == null || isInsideFlush()) {
            return false;
        }
        boolean accepted = queue.offer(entry);
        if (!accepted) {
            long total = dropped.incrementAndGet();
            if (total % DROP_LOG_EVERY == 1) {
                // Логируем сами, но уже с флагом — иначе это WARN снова придёт в очередь.
                withFlushGuard(() -> log.warn("Error-log queue is full, dropped {} event(s) so far", total));
            }
        }
        return accepted;
    }

    /**
     * Синхронно сливает всё, что накопилось. Публичный метод нужен тестам, чтобы не
     * ждать фонового тика.
     *
     * @return число фактически записанных строк
     */
    public int flush() {
        int written = 0;
        List<ErrorLogEntry> batch = new ArrayList<>(properties.batchSize());
        while (queue.drainTo(batch, properties.batchSize()) > 0) {
            repository.insertBatch(List.copyOf(batch));
            written += batch.size();
            batch.clear();
        }
        return written;
    }

    /** Число событий, отброшенных из-за переполнения очереди с момента старта. */
    public long droppedCount() {
        return dropped.get();
    }

    /** Текущий размер очереди — для диагностики и тестов. */
    public int queueSize() {
        return queue.size();
    }

    private void flushQuietly() {
        withFlushGuard(() -> {
            try {
                flush();
            } catch (Exception e) {
                // Сюда попадаем при недоступной БД. Ронять фоновый поток нельзя —
                // scheduleWithFixedDelay после исключения больше не запустится.
                log.warn("Failed to flush error log batch: {}", e.getMessage());
            }
        });
    }

    private void withFlushGuard(Runnable body) {
        boolean outer = isInsideFlush();
        IN_FLUSH.set(Boolean.TRUE);
        try {
            body.run();
        } finally {
            if (outer) {
                IN_FLUSH.set(Boolean.TRUE);
            } else {
                IN_FLUSH.remove();
            }
        }
    }
}

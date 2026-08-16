package com.plantcare.bot.service;

import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.service.NotificationDeliveryRecorder;
import com.plantcare.core.service.PushSender;
import com.plantcare.core.service.PushSender.PushResult;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Мультиканальный push-fan-out для шедулера напоминаний (issue #177, ADR-014).
 *
 * <p>Шедулер ({@link NotificationSchedulerService}) исполняет тик внутри открытой
 * {@code @Transactional}-транзакции. CLAUDE.md запрещает вызовы внешних API
 * (Telegram / FCM / APNs) внутри открытой транзакции к БД — поэтому фактическая
 * раздача push'ей вынесена сюда и исполняется на выделенном daemon-исполнителе
 * ВНЕ транзакции тика. Шедулер отдаёт только примитивные DTO ({@link PushTarget}),
 * не таща detached JPA-entity между потоками.
 *
 * <p>Bookkeeping (метрика доставки в канал {@link com.plantcare.core.metrics.MetricsService#CHANNEL_PUSH},
 * удаление протухшего device-токена на {@link PushResult#STALE_TOKEN}) выполняет
 * {@link NotificationDeliveryCallbacks}, каждый метод которого открывает
 * собственную транзакцию на воркер-потоке.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PushFanOutService {

    /** Заголовок push-уведомления (канал-нейтральный, как и текст задачи). */
    public static final String PUSH_TITLE = "Plants Care";

    private final PushSender pushSender;
    private final NotificationDeliveryCallbacks deliveryCallbacks;
    private final NotificationDeliveryRecorder deliveryRecorder;

    /**
     * Однопоточный daemon-исполнитель: гарантирует, что доставка идёт ВНЕ
     * транзакции тика, не блокирует шедулер и не держит лишних потоков.
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "push-fanout");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Поставить push-раздачу по списку устройств в исполнение ВНЕ транзакции
     * вызывающего тика. Метод не блокирует шедулер: вся доставка и bookkeeping
     * происходят на воркер-потоке.
     *
     * @param targets устройства с готовым канал-нейтральным текстом
     */
    public void enqueue(List<PushTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        executor.execute(() -> {
            for (PushTarget target : targets) {
                deliver(target);
            }
        });
    }

    /**
     * Доставить один push и сделать bookkeeping по исходу. Package-private —
     * чтобы юнит-тест мог дёргать синхронно с замоканным {@link PushSender}.
     * Любая ошибка отдельного устройства не прерывает остальные (вызывающий цикл).
     */
    void deliver(PushTarget target) {
        long startNanos = System.nanoTime();
        try {
            PushResult result = pushSender.send(target.pushToken(), PUSH_TITLE, target.body());
            // Issue #95: журнал доставки пишем ДО колбэков — на STALE_TOKEN колбэк
            // сносит запись устройства, а событие должно остаться с user_id.
            deliveryRecorder.recordPush(target.userId(), result, elapsedMillis(startNanos));
            switch (result) {
                case SENT -> deliveryCallbacks.onPushSent(target.taskType());
                case STALE_TOKEN -> deliveryCallbacks.onPushStaleToken(target.deviceId());
                case FAILED -> deliveryCallbacks.onPushFailed();
            }
        } catch (Exception e) {
            // Порт по контракту не должен бросать, но защищаемся: падение одного
            // устройства не должно ронять воркер и остальные устройства.
            log.warn("Push delivery threw for device id={}: {}", target.deviceId(), e.getMessage());
            deliveryRecorder.recordPush(target.userId(), PushResult.FAILED, elapsedMillis(startNanos));
            deliveryCallbacks.onPushFailed();
        }
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Push fan-out executor stopped");
    }

    /**
     * Канал-нейтральная цель push-доставки (issue #177): примитивы, безопасные
     * для передачи между потоками (никаких detached-entity).
     *
     * @param deviceId  id записи устройства в {@code user_devices} (для удаления на STALE_TOKEN)
     * @param userId    владелец устройства (issue #95 — per-user срез журнала доставок)
     * @param pushToken push-токен устройства
     * @param body      готовый канал-нейтральный текст уведомления
     * @param taskType  тип задачи (для среза метрики доставки)
     */
    public record PushTarget(long deviceId, Long userId, String pushToken, String body, TaskType taskType) {
    }
}

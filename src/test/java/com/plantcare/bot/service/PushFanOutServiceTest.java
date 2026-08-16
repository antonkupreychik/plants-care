package com.plantcare.bot.service;

import com.plantcare.core.config.NoopPushSender;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.service.PushSender;
import com.plantcare.core.service.PushSender.PushResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link PushFanOutService} (issue #177) — маршрутизация исхода
 * {@link PushResult} из порта {@link PushSender} в bookkeeping-колбэки
 * {@link NotificationDeliveryCallbacks}.
 *
 * <p>Доставка отвязана от транзакции тика и исполняется на daemon-исполнителе;
 * чтобы тесты были детерминированы, дёргаем package-private {@link
 * PushFanOutService#deliver} напрямую (синхронно), с замоканным {@link PushSender}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для PushFanOutService (issue #177)")
class PushFanOutServiceTest {

    @Mock
    private PushSender pushSender;

    @Mock
    private NotificationDeliveryCallbacks deliveryCallbacks;

    @InjectMocks
    private PushFanOutService service;

    private static PushFanOutService.PushTarget target() {
        return new PushFanOutService.PushTarget(42L, "token-abc", "Пора полить: Алоэ", TaskType.WATERING);
    }

    @Test
    @DisplayName("SENT → onPushSent с типом задачи, токен НЕ удаляется")
    void should_record_sent_when_push_delivered() {
        when(pushSender.send(eq("token-abc"), anyString(), eq("Пора полить: Алоэ")))
                .thenReturn(PushResult.SENT);

        service.deliver(target());

        verify(deliveryCallbacks).onPushSent(TaskType.WATERING);
        verify(deliveryCallbacks, never()).onPushStaleToken(anyLong());
        verify(deliveryCallbacks, never()).onPushFailed();
    }

    @Test
    @DisplayName("STALE_TOKEN (FCM UNREGISTERED/NOT_FOUND) → удаление протухшего устройства по id")
    void should_remove_stale_device_when_token_unregistered() {
        when(pushSender.send(anyString(), anyString(), anyString()))
                .thenReturn(PushResult.STALE_TOKEN);

        service.deliver(target());

        verify(deliveryCallbacks).onPushStaleToken(42L);
        verify(deliveryCallbacks, never()).onPushSent(any());
    }

    @Test
    @DisplayName("FAILED (сеть/5xx) → onPushFailed, токен остаётся для повтора")
    void should_record_failed_when_delivery_fails_transiently() {
        when(pushSender.send(anyString(), anyString(), anyString()))
                .thenReturn(PushResult.FAILED);

        service.deliver(target());

        verify(deliveryCallbacks).onPushFailed();
        verify(deliveryCallbacks, never()).onPushStaleToken(anyLong());
    }

    @Test
    @DisplayName("Исключение из порта не роняет воркер — считается за onPushFailed")
    void should_treat_thrown_exception_as_failed() {
        when(pushSender.send(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("provider exploded"));

        service.deliver(target());

        verify(deliveryCallbacks).onPushFailed();
    }

    @Test
    @DisplayName("enqueue по нескольким устройствам доставляет каждое (single-thread executor)")
    void should_deliver_each_target_when_enqueued() throws Exception {
        when(pushSender.send(anyString(), anyString(), anyString())).thenReturn(PushResult.SENT);

        service.enqueue(List.of(
                new PushFanOutService.PushTarget(1L, "t1", "body", TaskType.WATERING),
                new PushFanOutService.PushTarget(2L, "t2", "body", TaskType.MISTING)));

        // executor однопоточный — дожидаемся дренирования через PreDestroy shutdown.
        invokeShutdown();

        verify(pushSender, times(2)).send(anyString(), anyString(), anyString());
        verify(deliveryCallbacks).onPushSent(TaskType.WATERING);
        verify(deliveryCallbacks).onPushSent(TaskType.MISTING);
    }

    @Test
    @DisplayName("push.enabled=false (NoopPushSender) → SENT, путь не ломается")
    void should_not_break_when_push_disabled_noop_sender() {
        PushFanOutService noopService =
                new PushFanOutService(new NoopPushSender(), deliveryCallbacks);

        noopService.deliver(target());

        verify(deliveryCallbacks).onPushSent(TaskType.WATERING);
        verifyNoInteractions(pushSender);
    }

    /** Дёргает @PreDestroy shutdown рефлексией, чтобы дождаться дренирования executor'а. */
    private void invokeShutdown() throws Exception {
        var method = PushFanOutService.class.getDeclaredMethod("shutdown");
        method.setAccessible(true);
        method.invoke(service);
    }

    // anyLong — отдельный импорт через статический хелпер, чтобы не тащить весь ArgumentMatchers.
    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}

package com.plantcare.bot.service;

import com.plantcare.core.service.NotificationDeliveryRecorder;
import com.plantcare.core.service.PushSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Branch-тесты {@link PushFanOutService#enqueue(List)} для веток раннего выхода
 * ({@code targets == null} / {@code targets.isEmpty()}), не покрытых
 * {@link PushFanOutServiceTest} (там enqueue всегда вызывается с непустым списком).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PushFanOutService.enqueue — branch-покрытие ранних выходов")
class PushFanOutServiceBranchTest {

    @Mock
    private PushSender pushSender;

    @Mock
    private NotificationDeliveryCallbacks deliveryCallbacks;

    @Mock
    private NotificationDeliveryRecorder deliveryRecorder;

    @InjectMocks
    private PushFanOutService service;

    @Test
    @DisplayName("enqueue(null) — ранний выход, никаких взаимодействий с портом/колбэками")
    void should_do_nothing_when_targets_is_null() {
        service.enqueue(null);

        verifyNoInteractions(pushSender, deliveryCallbacks, deliveryRecorder);
    }

    @Test
    @DisplayName("enqueue(emptyList()) — ранний выход, никаких взаимодействий с портом/колбэками")
    void should_do_nothing_when_targets_is_empty() {
        service.enqueue(List.of());

        verifyNoInteractions(pushSender, deliveryCallbacks, deliveryRecorder);
    }
}

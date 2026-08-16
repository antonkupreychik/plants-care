package com.plantcare.core.service;

import com.plantcare.core.domain.NotificationDeliveryEvent;
import com.plantcare.core.domain.enums.DeliveryChannel;
import com.plantcare.core.domain.enums.DeliveryStatus;
import com.plantcare.core.metrics.MetricsService.TelegramErrorCode;
import com.plantcare.core.repository.NotificationDeliveryEventRepository;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.core.service.PushSender.PushResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * Пишет журнал попыток доставки уведомлений (issue #95) — источник данных для
 * health-дашборда {@code /admin/notifications/health}.
 *
 * <p>Дополняет, а не заменяет {@code MetricsService}: Prometheus-счётчики
 * низкокардинальные и не помнят, КОМУ не доставилось. Дашборду нужен именно
 * per-user срез («у кого 3 фейла подряд») и топ конкретных кодов ошибок —
 * это ряды в БД, а не тэги метрик.
 *
 * <p><b>Транзакции.</b> Собственного {@code @Transactional} нет намеренно: методы
 * зовутся с воркер-потоков очередей доставки, где ambient-транзакции нет, а
 * {@code SimpleJpaRepository.save} открывает свою. Плюс это позволяет держать
 * try/catch прямо здесь — журнал наблюдаемости не имеет права уронить доставку.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDeliveryRecorder {

    /** Префикс кода ошибки Telegram-канала: {@code telegram:403}. */
    public static final String TELEGRAM_ERROR_PREFIX = "telegram:";
    /** Префикс кода ошибки push-канала: {@code fcm:UnregisteredDevice}. */
    public static final String PUSH_ERROR_PREFIX = "fcm:";

    /** FCM {@code UNREGISTERED}/{@code INVALID_ARGUMENT} — токен мёртв, устройство пора отцепить. */
    public static final String PUSH_ERROR_STALE_TOKEN = PUSH_ERROR_PREFIX + "UnregisteredDevice";
    /** Прочая ошибка доставки push: сеть, 5xx провайдера. */
    public static final String PUSH_ERROR_DELIVERY = PUSH_ERROR_PREFIX + "DeliveryError";

    private final NotificationDeliveryEventRepository eventRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /**
     * Зафиксировать успешную отправку в Telegram.
     *
     * @param chatId    chat_id получателя; {@code null}, если распарсить не удалось
     * @param latencyMs длительность вызова Telegram Bot API
     */
    public void recordTelegramSent(Long chatId, long latencyMs) {
        record(DeliveryChannel.TELEGRAM, resolveUserId(chatId), DeliveryStatus.SENT, null, latencyMs);
    }

    /**
     * Зафиксировать неудачу отправки в Telegram. 429 отделяется от прочих ошибок
     * отдельным статусом — по нему дашборд отличает «нас душит лимит» от
     * «токен/чат сломан».
     *
     * @param chatId    chat_id получателя; {@code null}, если распарсить не удалось
     * @param code      код ответа Telegram, распарсенный из текста исключения
     * @param latencyMs длительность вызова Telegram Bot API
     */
    public void recordTelegramFailure(Long chatId, TelegramErrorCode code, long latencyMs) {
        TelegramErrorCode safeCode = code == null ? TelegramErrorCode.OTHER : code;
        DeliveryStatus status = safeCode == TelegramErrorCode.RATE_LIMITED
                ? DeliveryStatus.RATE_LIMITED
                : DeliveryStatus.FAILED;
        record(DeliveryChannel.TELEGRAM, resolveUserId(chatId), status,
                TELEGRAM_ERROR_PREFIX + safeCode.tagValue(), latencyMs);
    }

    /**
     * Зафиксировать исход push-доставки на одно устройство.
     *
     * @param userId    владелец устройства; {@code null} допустим
     * @param result    исход, как его вернул порт {@link PushSender}
     * @param latencyMs длительность вызова провайдера
     */
    public void recordPush(Long userId, PushResult result, long latencyMs) {
        DeliveryStatus status = result == PushResult.SENT ? DeliveryStatus.SENT : DeliveryStatus.FAILED;
        String errorCode = switch (result) {
            case SENT -> null;
            case STALE_TOKEN -> PUSH_ERROR_STALE_TOKEN;
            case FAILED -> PUSH_ERROR_DELIVERY;
        };
        record(DeliveryChannel.PUSH, userId, status, errorCode, latencyMs);
    }

    /**
     * Единственная точка записи. Любая ошибка журналирования проглатывается:
     * наблюдаемость не должна ломать доставку (и не должна ронять единственный
     * воркер-поток очереди).
     */
    private void record(DeliveryChannel channel, Long userId, DeliveryStatus status,
                        String errorCode, long latencyMs) {
        try {
            eventRepository.save(new NotificationDeliveryEvent(
                    clock.instant(), channel, userId, status, errorCode, clampLatency(latencyMs)));
        } catch (Exception e) {
            log.warn("Failed to record delivery event (channel={}, status={}): {}",
                    channel, status, e.getMessage());
        }
    }

    /** Отрицательных и переполняющих {@code INTEGER} значений в колонке быть не должно. */
    private static Integer clampLatency(long latencyMs) {
        if (latencyMs < 0) {
            return 0;
        }
        return (int) Math.min(latencyMs, Integer.MAX_VALUE);
    }

    /**
     * Резолв {@code chat_id → users.id}. Промах (чат неизвестен) — не ошибка:
     * пишем событие с {@code user_id = NULL}, канальные агрегаты от этого не
     * страдают, страдает только per-user срез по этой строке.
     */
    private Long resolveUserId(Long chatId) {
        if (chatId == null) {
            return null;
        }
        try {
            return userRepository.findIdByTelegramChatId(chatId).orElse(null);
        } catch (Exception e) {
            log.warn("Failed to resolve user by chatId={}: {}", chatId, e.getMessage());
            return null;
        }
    }
}

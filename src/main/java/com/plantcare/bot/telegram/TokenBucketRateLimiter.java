package com.plantcare.bot.telegram;

import com.plantcare.bot.config.TelegramRateLimitProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Token bucket для соблюдения лимита Telegram на массовую отправку (issue #29).
 *
 * <p>Ёмкость ведра = {@code permitsPerSecond}, пополняется с той же скоростью
 * ({@code permitsPerSecond} токенов в секунду). Метод {@link #reserveNextPermitMillis()}
 * резервирует один токен и возвращает, сколько миллисекунд вызывающему нужно
 * подождать перед его использованием (0 — токен доступен немедленно).
 *
 * <p>Время берётся только из инжектируемого {@link Clock} — никаких прямых
 * {@code Instant.now()}, чтобы тест мог детерминированно прогнать пополнение
 * через мутабельный тест-clock.
 *
 * <p>Потокобезопасность через {@code synchronized}: очередь дренирует один
 * воркер-поток, конкуренции почти нет, простота важнее микро-оптимизации.
 */
@Component
@RequiredArgsConstructor
public class TokenBucketRateLimiter {

    private final TelegramRateLimitProperties properties;
    private final Clock clock;

    /**
     * Доступные токены (дробные — накапливаются между вызовами). Изначально ведро
     * полное, чтобы первый всплеск до {@code permitsPerSecond} ушёл без задержки.
     */
    private double availableTokens;

    /** Время последнего пополнения, мс эпохи (из {@code clock}). */
    private long lastRefillMillis = Long.MIN_VALUE;

    /**
     * Зарезервировать следующий токен. Возвращает сколько миллисекунд подождать
     * до момента, когда зарезервированный токен станет доступен (0 — сейчас).
     *
     * <p><b>Инвариант единственного потребителя:</b> метод рассчитан на ОДНОГО
     * потребителя (воркер-поток очереди), который ОБЯЗАТЕЛЬНО проспит возвращённую
     * паузу перед следующим вызовом. {@code lastRefillMillis} не двигается на
     * зарезервированный «будущий» слот — баланс лишь уходит в минус «в долг».
     * Если бы вызовов было несколько подряд без ожидания (или из нескольких
     * потоков), пополнение посчиталось бы заново от того же момента и реальная
     * скорость превысила бы {@code permitsPerSecond}.
     */
    public synchronized long reserveNextPermitMillis() {
        long permitsPerSecond = Math.max(1, properties.getPermitsPerSecond());
        long nowMillis = clock.millis();

        if (lastRefillMillis == Long.MIN_VALUE) {
            // Первый вызов: ведро полное.
            lastRefillMillis = nowMillis;
            availableTokens = permitsPerSecond;
        } else {
            double elapsedSeconds = (nowMillis - lastRefillMillis) / 1000.0;
            if (elapsedSeconds > 0) {
                availableTokens = Math.min(
                        permitsPerSecond,
                        availableTokens + elapsedSeconds * permitsPerSecond);
                lastRefillMillis = nowMillis;
            }
        }

        if (availableTokens >= 1.0) {
            availableTokens -= 1.0;
            return 0L;
        }

        // Не хватает целого токена — сколько мс до накопления недостающей доли.
        double missing = 1.0 - availableTokens;
        double secondsToWait = missing / permitsPerSecond;
        long waitMillis = (long) Math.ceil(secondsToWait * 1000.0);

        // Резервируем токен «в долг»: уводим баланс в минус, чтобы следующий
        // вызов считал паузу от уже зарезервированного момента, а не повторно.
        availableTokens -= 1.0;
        return waitMillis;
    }
}

package com.plantcare.bot.telegram;

import org.springframework.stereotype.Component;

/**
 * Тонкая абстракция над {@link Thread#sleep(long)} (issue #29).
 *
 * <p>Нужна исключительно для тестируемости: путь отправки с ретраями на 429 и
 * пауза token bucket'а должны проверяться юнит-тестом без реальных задержек в
 * десятки секунд. В проде работает дефолтный бин {@link Default}.
 */
public interface Sleeper {

    void sleep(long millis) throws InterruptedException;

    /** Боевая реализация — обычный {@link Thread#sleep(long)}. */
    @Component
    class Default implements Sleeper {
        @Override
        public void sleep(long millis) throws InterruptedException {
            if (millis > 0) {
                Thread.sleep(millis);
            }
        }
    }
}

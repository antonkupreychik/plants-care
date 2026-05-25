package com.plantcare.bot.telegram;

import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.function.Consumer;

/**
 * Колбэки, которые воркер очереди вызывает ПОСЛЕ резолва отправки (issue #29).
 *
 * <p>Выполняются на воркер-потоке очереди, ВНЕ Spring-управляемой транзакции
 * шедулера. Поэтому в них нельзя протаскивать detached JPA-entity — захватывайте
 * только примитивные идентификаторы и перерезолвливайте сущности в собственной
 * транзакции коллаборатора.
 *
 * @param onSuccess вызывается после успешной отправки (может быть {@code null})
 * @param onFailure вызывается после неретраябельной ошибки или исчерпания ретраев
 *                  (может быть {@code null})
 */
public record SendCallbacks(Runnable onSuccess, Consumer<TelegramApiException> onFailure) {

    private static final SendCallbacks NONE = new SendCallbacks(null, null);

    /** Колбэки-заглушки: ничего не делают. */
    public static SendCallbacks none() {
        return NONE;
    }

    void runSuccess() {
        if (onSuccess != null) {
            onSuccess.run();
        }
    }

    void runFailure(TelegramApiException e) {
        if (onFailure != null) {
            onFailure.accept(e);
        }
    }
}

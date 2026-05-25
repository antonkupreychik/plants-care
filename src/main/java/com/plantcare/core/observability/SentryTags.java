package com.plantcare.core.observability;

import io.sentry.IScope;
import io.sentry.Sentry;

import java.util.function.Supplier;

/**
 * Хелпер для проставления тегов события Sentry (issue #114).
 *
 * <p>Теги ({@code layer}, {@code feature}) описывают, из какого слоя/фичи пришёл
 * апдейт или запрос:
 * <ul>
 *   <li>{@code layer} — слой приложения (см. {@link Layer});</li>
 *   <li>{@code feature} — имя бина-сервиса, где произошла обработка (для группировки
 *       по фиче в Sentry).</li>
 * </ul>
 *
 * <p><b>Изоляция scope (issue #114, blocking).</b> {@link Sentry#setTag} пишет в
 * thread-bound scope. На HTTP-потоках стартер push/pop'ает scope per-request, но на
 * shared-потоках (poll-поток Telegram, единственный поток {@code @Scheduled}) scope
 * НЕ сбрасывается между задачами — теги «протекают» из одной единицы работы в
 * следующую (напр. {@code layer=weather} прилипает к scheduler-событию). Поэтому
 * единицу работы нужно оборачивать в {@link #runWithLayer}/{@link #callWithLayer},
 * которые через {@link Sentry#withScope(io.sentry.ScopeCallback)} проставляют теги на
 * КЛОНИРОВАННЫЙ scope только на время операции и гарантированно откатывают его после.
 * {@code Sentry.captureException}, вызванный ВНУТРИ лямбды, видит корректные теги и не
 * загрязняет внешний scope.
 *
 * <p>Когда Sentry выключен/no-op, все методы безопасно ничего не делают.
 */
public final class SentryTags {

    public static final String TAG_LAYER = "layer";
    public static final String TAG_FEATURE = "feature";

    /** Слой приложения для тега {@code layer}. */
    public enum Layer {
        TELEGRAM("telegram"),
        SCHEDULER("scheduler"),
        REST("rest"),
        ADMIN("admin"),
        WEATHER("weather");

        private final String value;

        Layer(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    private SentryTags() {
    }

    /** Проставить теги {@code layer}/{@code feature} на переданный scope. */
    public static void mark(IScope scope, Layer layer, String feature) {
        scope.setTag(TAG_LAYER, layer.value());
        if (feature != null && !feature.isBlank()) {
            scope.setTag(TAG_FEATURE, feature);
        }
    }

    /**
     * Выполнить {@code body} в изолированном scope с проставленными {@code layer}/
     * {@code feature}. Теги живут только на время выполнения и откатываются после —
     * не текут на следующую задачу shared-потока. {@code Sentry.captureException}
     * внутри {@code body} получит корректные теги.
     */
    public static void runWithLayer(Layer layer, String feature, Runnable body) {
        Sentry.withScope(scope -> {
            mark(scope, layer, feature);
            body.run();
        });
    }

    /**
     * Версия {@link #runWithLayer} с возвращаемым значением. {@link Sentry#withScope}
     * сам по себе значения не возвращает, поэтому прокидываем результат через
     * однопроходный holder.
     */
    public static <T> T callWithLayer(Layer layer, String feature, Supplier<T> body) {
        var holder = new Object() {
            T result;
        };
        Sentry.withScope(scope -> {
            mark(scope, layer, feature);
            holder.result = body.get();
        });
        return holder.result;
    }
}

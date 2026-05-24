package com.plantcare.bot.observability;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions.BeforeSendCallback;
import io.sentry.protocol.Message;
import io.sentry.protocol.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PII-фильтр для событий Sentry (issue #114).
 *
 * <p>Регистрируется как {@link BeforeSendCallback} в опциях Sentry-стартера
 * (см. {@link SentryConfig}). Перед отправкой любого события:
 * <ul>
 *   <li>{@code chat_id} / {@code telegram_user_id} в тегах и extra — заменяются
 *       на короткий SHA-256 хеш (PII не уходит в открытом виде);</li>
 *   <li>идентификатор пользователя ({@link User}) хешируется тем же способом,
 *       e-mail/username/IP вычищаются;</li>
 *   <li>тексты сообщений юзера (содержимое заметок, имена растений) в message и
 *       extra — маскируются заглушкой, чтобы не утекали в Sentry.</li>
 * </ul>
 *
 * <p>Вся логика маскирования вынесена в чистый package-private метод
 * {@link #sanitize(SentryEvent)}, который не зависит от живого Sentry-хаба —
 * это точка юнит-тестируемости (отдельный тест напишет другой агент).
 */
@Slf4j
@Component
public class SentryPiiFilter implements BeforeSendCallback {

    /** Длина hex-префикса хеша — достаточно для дедупликации, но не обратима к id. */
    static final int HASH_PREFIX_LEN = 16;

    /** Заглушка вместо вырезанного текста юзера. */
    static final String REDACTED = "[redacted]";

    /** Ключи тегов/extra, значение которых считается PII-идентификатором и хешируется. */
    static final Set<String> ID_KEYS = Set.of(
            "chat_id", "chatId",
            "telegram_user_id", "telegramUserId", "telegram_chat_id", "telegramChatId"
    );

    /**
     * Ключи extra, значение которых — свободный пользовательский текст (имена
     * растений, заметки). Полностью маскируются.
     */
    static final Set<String> FREE_TEXT_KEYS = Set.of(
            "plant_name", "plantName",
            "note", "notes",
            "message_text", "messageText",
            "user_text", "userText",
            "callback_data", "callbackData"
    );

    @Override
    @Nullable
    public SentryEvent execute(@NonNull SentryEvent event, @NonNull Hint hint) {
        return sanitize(event);
    }

    /**
     * Чистая логика маскирования над {@link SentryEvent}. Возвращает тот же event
     * (мутирует его на месте, как и ожидает Sentry SDK). Не обращается к Sentry-хабу,
     * поэтому полностью юнит-тестируема без живого SDK.
     */
    @NonNull
    SentryEvent sanitize(@NonNull SentryEvent event) {
        sanitizeTags(event);
        sanitizeExtras(event);
        sanitizeUser(event);
        sanitizeMessage(event);
        sanitizeBreadcrumbs(event);
        // Сообщение эксепшена (event.getThrowable().getMessage()) НЕ затираем:
        // (1) Throwable иммутабелен — message задаётся в конструкторе, сеттера нет,
        //     правка только через reflection, что хрупко и небезопасно;
        // (2) полное затирание убило бы диагностику (стек без текста бесполезен).
        // Текущие бизнес-исключения кладут в message максимум справочные данные
        // (напр. имя ВИДА растения в DuplicateSpeciesNameException — это admin-
        // curated reference, не персональный текст юзера). Заметки/клички растений,
        // которые AC запрещает отправлять, в message исключений не попадают. Если в
        // будущем появится исключение, несущее такой текст, санитизировать его нужно в
        // самом throw. Основной авто-PII-канал — breadcrumbs — закрыт ниже.
        return event;
    }

    private void sanitizeTags(SentryEvent event) {
        Map<String, String> tags = event.getTags();
        if (tags == null) {
            return;
        }
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            if (ID_KEYS.contains(entry.getKey())) {
                entry.setValue(hash(entry.getValue()));
            } else if (FREE_TEXT_KEYS.contains(entry.getKey())) {
                entry.setValue(REDACTED);
            }
        }
    }

    private void sanitizeExtras(SentryEvent event) {
        Map<String, Object> extras = event.getExtras();
        if (extras == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : extras.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (ID_KEYS.contains(key)) {
                entry.setValue(hash(String.valueOf(value)));
            } else if (FREE_TEXT_KEYS.contains(key)) {
                entry.setValue(REDACTED);
            }
        }
    }

    private void sanitizeUser(SentryEvent event) {
        User user = event.getUser();
        if (user == null) {
            return;
        }
        if (user.getId() != null) {
            user.setId(hash(user.getId()));
        }
        // Никакой остальной PII в Sentry не отправляем.
        user.setEmail(null);
        user.setUsername(null);
        user.setIpAddress(null);
    }

    private void sanitizeMessage(SentryEvent event) {
        Message message = event.getMessage();
        if (message == null) {
            return;
        }
        // formatted — это отрендеренная строка с подставленными значениями юзера
        // (имя растения, заметка). Маскируем ВСЕГДА, независимо от наличия шаблона
        // и текущего значения: именно formatted содержит PII.
        message.setFormatted(REDACTED);
        // params — конкретные значения, подставляемые в шаблон (тоже могут быть
        // именами растений/заметками). Заменяем каждое на заглушку.
        List<String> params = message.getParams();
        if (params != null && !params.isEmpty()) {
            message.setParams(params.stream().map(p -> REDACTED).toList());
        }
        // message.getMessage() — статический параметризованный шаблон
        // (напр. "failed to send reminder for plant {}"). PII не содержит, оставляем.
    }

    /**
     * Breadcrumbs — автоматический PII-канал: SDK/логи могут осесть в них текстом
     * сообщений юзера (содержимое заметок, имена растений, callback_data). AC #114
     * прямо требует «не отправлять текст сообщений юзера», поэтому защитно:
     * <ul>
     *   <li>{@code message} крошки маскируем заглушкой (это свободный текст — может
     *       содержать эхо пользовательского ввода из лога);</li>
     *   <li>{@code data} крошки чистим по тем же правилам, что extras: id-ключи
     *       хешируем, free-text-ключи и любые прочие строковые значения маскируем
     *       (строка в breadcrumb.data — потенциальный носитель PII).</li>
     * </ul>
     */
    private void sanitizeBreadcrumbs(SentryEvent event) {
        List<Breadcrumb> breadcrumbs = event.getBreadcrumbs();
        if (breadcrumbs == null || breadcrumbs.isEmpty()) {
            return;
        }
        for (Breadcrumb crumb : breadcrumbs) {
            if (crumb.getMessage() != null) {
                crumb.setMessage(REDACTED);
            }
            Map<String, Object> data = crumb.getData();
            if (data == null || data.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value == null) {
                    continue;
                }
                if (ID_KEYS.contains(key)) {
                    entry.setValue(hash(String.valueOf(value)));
                } else if (value instanceof String) {
                    // Любая строка в data может быть PII — заглушаем. Числа/booleans
                    // (статусы, коды, длительности) оставляем для диагностики.
                    entry.setValue(REDACTED);
                }
            }
        }
    }

    /**
     * Короткий стабильный хеш идентификатора: SHA-256 → первые
     * {@link #HASH_PREFIX_LEN} hex-символов. Детерминирован (одинаковый chat_id даёт
     * одинаковый хеш → дедуп в Sentry работает), но не обратим к исходному id.
     */
    @NonNull
    String hash(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return REDACTED;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(bytes);
            return hex.substring(0, Math.min(HASH_PREFIX_LEN, hex.length()));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 есть в любой стандартной JVM; сюда не попадём. Подстраховка —
            // не отправлять сырой id, лучше потерять значение.
            log.warn("SHA-256 unavailable, redacting id instead of hashing");
            return REDACTED;
        }
    }
}

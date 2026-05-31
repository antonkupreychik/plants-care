package com.plantcare.core.observability;

import io.sentry.Breadcrumb;
import io.sentry.SentryEvent;
import io.sentry.protocol.Message;
import io.sentry.protocol.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Юнит-тесты PII-фильтра Sentry (issue #114). Без Spring/Testcontainers/живого Sentry-хаба —
 * только {@code new SentryPiiFilter()} и собранный руками {@link SentryEvent}.
 */
class SentryPiiFilterTest {

    private SentryPiiFilter filter;

    @BeforeEach
    void setUp() {
        filter = new SentryPiiFilter();
    }

    // ---------------------------------------------------------------------
    // 1. chat_id / telegram_user_id маскируются (happy path)
    // ---------------------------------------------------------------------

    @Test
    void should_hash_id_tags_when_snake_case_id_keys_present() {
        // arrange
        var event = new SentryEvent();
        event.setTag("chat_id", "123456789");
        event.setTag("telegram_user_id", "42");

        // act
        filter.sanitize(event);

        // assert
        assertThat(event.getTags())
                .containsEntry("chat_id", filter.hash("123456789"))
                .containsEntry("telegram_user_id", filter.hash("42"));
        assertThat(event.getTags().get("chat_id")).isNotEqualTo("123456789");
    }

    @Test
    void should_hash_id_tags_when_camel_case_id_keys_present() {
        // arrange
        var event = new SentryEvent();
        event.setTag("chatId", "987654321");
        event.setTag("telegramUserId", "7");
        event.setTag("telegramChatId", "555");

        // act
        filter.sanitize(event);

        // assert
        assertThat(event.getTags())
                .containsEntry("chatId", filter.hash("987654321"))
                .containsEntry("telegramUserId", filter.hash("7"))
                .containsEntry("telegramChatId", filter.hash("555"));
    }

    @Test
    void should_hash_id_extra_when_numeric_value_in_id_key() {
        // arrange
        var event = new SentryEvent();
        event.setExtra("telegram_user_id", 42L);
        event.setExtra("chatId", 123456789L);

        // act
        filter.sanitize(event);

        // assert
        assertThat(event.getExtras())
                .containsEntry("telegram_user_id", filter.hash("42"))
                .containsEntry("chatId", filter.hash("123456789"));
    }

    @Test
    void should_produce_hex_value_of_prefix_length_when_hashing_id_tag() {
        // arrange
        var event = new SentryEvent();
        event.setTag("chat_id", "123456789");

        // act
        filter.sanitize(event);

        // assert
        String hashed = event.getTags().get("chat_id");
        assertThat(hashed)
                .hasSize(SentryPiiFilter.HASH_PREFIX_LEN)
                .matches("[0-9a-f]+");
    }

    // ---------------------------------------------------------------------
    // 2. Имена растений / заметки НЕ уходят (FREE_TEXT_KEYS) — явный AC
    // ---------------------------------------------------------------------

    @Test
    void should_redact_free_text_tags_when_user_text_keys_present() {
        // arrange
        var event = new SentryEvent();
        event.setTag("plant_name", "Фикус Бенджамина");
        event.setTag("note", "полить через 3 дня");
        event.setTag("message_text", "/start secret");
        event.setTag("callback_data", "delete:42");

        // act
        filter.sanitize(event);

        // assert
        assertThat(event.getTags())
                .containsEntry("plant_name", SentryPiiFilter.REDACTED)
                .containsEntry("note", SentryPiiFilter.REDACTED)
                .containsEntry("message_text", SentryPiiFilter.REDACTED)
                .containsEntry("callback_data", SentryPiiFilter.REDACTED);
    }

    @Test
    void should_redact_free_text_extra_when_user_text_keys_present() {
        // arrange
        var event = new SentryEvent();
        event.setExtra("plantName", "Монстера");
        event.setExtra("notes", "заметка пользователя");
        event.setExtra("userText", "что-то приватное");
        event.setExtra("callbackData", "edit:7");

        // act
        filter.sanitize(event);

        // assert
        assertThat(event.getExtras())
                .containsEntry("plantName", SentryPiiFilter.REDACTED)
                .containsEntry("notes", SentryPiiFilter.REDACTED)
                .containsEntry("userText", SentryPiiFilter.REDACTED)
                .containsEntry("callbackData", SentryPiiFilter.REDACTED);
    }

    // ---------------------------------------------------------------------
    // 3. User PII вычищается
    // ---------------------------------------------------------------------

    @Test
    void should_hash_user_id_and_clear_other_pii_when_user_present() {
        // arrange
        var user = new User();
        user.setId("123456789");
        user.setEmail("user@example.com");
        user.setUsername("john_doe");
        user.setIpAddress("203.0.113.7");
        var event = new SentryEvent();
        event.setUser(user);

        // act
        filter.sanitize(event);

        // assert
        User sanitized = event.getUser();
        assertThat(sanitized.getId())
                .isEqualTo(filter.hash("123456789"))
                .isNotEqualTo("123456789");
        assertThat(sanitized.getEmail()).isNull();
        assertThat(sanitized.getUsername()).isNull();
        assertThat(sanitized.getIpAddress()).isNull();
    }

    @Test
    void should_clear_user_pii_when_user_id_is_null() {
        // arrange
        var user = new User();
        user.setEmail("user@example.com");
        user.setUsername("john_doe");
        var event = new SentryEvent();
        event.setUser(user);

        // act
        filter.sanitize(event);

        // assert
        User sanitized = event.getUser();
        assertThat(sanitized.getId()).isNull();
        assertThat(sanitized.getEmail()).isNull();
        assertThat(sanitized.getUsername()).isNull();
    }

    // ---------------------------------------------------------------------
    // 4. hash: детерминированность, необратимость, длина, null/blank
    // ---------------------------------------------------------------------

    @Test
    void should_return_same_hash_when_same_input_for_dedup() {
        // arrange
        String raw = "123456789";

        // act
        String first = filter.hash(raw);
        String second = filter.hash(raw);

        // assert
        assertThat(first).isEqualTo(second);
    }

    @Test
    void should_return_different_hash_when_different_input() {
        // arrange / act
        String a = filter.hash("123456789");
        String b = filter.hash("987654321");

        // assert
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_match_independent_sha256_prefix_when_hashing() throws NoSuchAlgorithmException {
        // arrange
        String raw = "telegram-chat-42";
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8));
        String expected = HexFormat.of().formatHex(digest).substring(0, SentryPiiFilter.HASH_PREFIX_LEN);

        // act
        String actual = filter.hash(raw);

        // assert
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void should_not_leak_raw_value_when_hashing() {
        // arrange
        String raw = "123456789";

        // act
        String hashed = filter.hash(raw);

        // assert
        assertThat(hashed)
                .isNotEqualTo(raw)
                .doesNotContain(raw)
                .hasSize(SentryPiiFilter.HASH_PREFIX_LEN);
    }

    @Test
    void should_redact_when_hash_input_is_null() {
        // arrange / act
        String result = filter.hash(null);

        // assert
        assertThat(result).isEqualTo(SentryPiiFilter.REDACTED);
    }

    @Test
    void should_redact_when_hash_input_is_blank() {
        // arrange / act
        String result = filter.hash("   ");

        // assert
        assertThat(result).isEqualTo(SentryPiiFilter.REDACTED);
    }

    // ---------------------------------------------------------------------
    // 5. edge / failure: NPE-safety, non-PII keys untouched, message behaviour
    // ---------------------------------------------------------------------

    @Test
    void should_not_throw_and_return_same_event_when_event_is_empty() {
        // arrange
        var event = new SentryEvent();

        // act / assert
        assertThatNoException().isThrownBy(() -> filter.sanitize(event));
        assertThat(filter.sanitize(event)).isSameAs(event);
    }

    @Test
    void should_leave_non_pii_tags_untouched_when_sanitizing() {
        // arrange
        var event = new SentryEvent();
        event.setTag("layer", "scheduler");
        event.setTag("feature", "watering-reminder");

        // act
        filter.sanitize(event);

        // assert
        assertThat(event.getTags())
                .containsEntry("layer", "scheduler")
                .containsEntry("feature", "watering-reminder");
    }

    @Test
    void should_leave_non_pii_extras_untouched_when_sanitizing() {
        // arrange
        var event = new SentryEvent();
        event.setExtra("attempt", 3);
        event.setExtra("region", "eu-central");

        // act
        filter.sanitize(event);

        // assert
        assertThat(event.getExtras())
                .containsEntry("attempt", 3)
                .containsEntry("region", "eu-central");
    }

    @Test
    void should_redact_formatted_message_when_template_message_is_null() {
        // arrange
        var message = new Message();
        message.setFormatted("Failed to water plant Фикус for user 42");
        // message.getMessage() остаётся null
        var event = new SentryEvent();
        event.setMessage(message);

        // act
        filter.sanitize(event);

        // assert
        assertThat(event.getMessage().getFormatted()).isEqualTo(SentryPiiFilter.REDACTED);
    }

    @Test
    void should_redact_formatted_when_template_message_is_present() {
        // arrange
        // formatted содержит интерполированный PII (имя растения, id юзера) —
        // его маскируем ВСЕГДА, даже когда задан статический шаблон message.
        // Сам шаблон message PII не содержит и должен остаться нетронутым.
        var message = new Message();
        message.setMessage("Failed to water plant {} for user {}");
        message.setFormatted("Failed to water plant Фикус Бенджамина for user 42");
        var event = new SentryEvent();
        event.setMessage(message);

        // act
        filter.sanitize(event);

        // assert
        assertThat(event.getMessage().getFormatted()).isEqualTo(SentryPiiFilter.REDACTED);
        assertThat(event.getMessage().getMessage()).isEqualTo("Failed to water plant {} for user {}");
    }

    @Test
    void should_redact_message_params_when_present() {
        // arrange
        var message = new Message();
        message.setMessage("Failed to water plant {} for user {}");
        message.setParams(List.of("Фикус Бенджамина", "42"));
        var event = new SentryEvent();
        event.setMessage(message);

        // act
        filter.sanitize(event);

        // assert
        assertThat(event.getMessage().getParams())
                .containsExactly(SentryPiiFilter.REDACTED, SentryPiiFilter.REDACTED);
        assertThat(event.getMessage().getMessage()).isEqualTo("Failed to water plant {} for user {}");
    }

    @Test
    void should_not_throw_when_message_present_without_formatted_or_template() {
        // arrange
        var event = new SentryEvent();
        event.setMessage(new Message());

        // act / assert
        assertThatNoException().isThrownBy(() -> filter.sanitize(event));
        assertThat(event.getMessage().getFormatted()).isEqualTo(SentryPiiFilter.REDACTED);
    }

    // ---------------------------------------------------------------------
    // 6. breadcrumbs — авто-PII-канал (новый шаг sanitizeBreadcrumbs)
    // ---------------------------------------------------------------------

    @Test
    void should_redact_breadcrumb_message_when_it_contains_user_text() {
        // arrange
        var crumb = new Breadcrumb();
        crumb.setMessage("user sent: полить Фикус через 3 дня");
        var event = new SentryEvent();
        event.addBreadcrumb(crumb);

        // act
        filter.sanitize(event);

        // assert
        assertThat(event.getBreadcrumbs())
                .singleElement()
                .extracting(Breadcrumb::getMessage)
                .isEqualTo(SentryPiiFilter.REDACTED);
    }

    @Test
    void should_hash_id_keys_in_breadcrumb_data_when_present() {
        // arrange
        var crumb = new Breadcrumb();
        crumb.setData("chat_id", "123456789");
        crumb.setData("telegram_user_id", 42L);
        var event = new SentryEvent();
        event.addBreadcrumb(crumb);

        // act
        filter.sanitize(event);

        // assert
        Map<String, Object> data = event.getBreadcrumbs().get(0).getData();
        assertThat(data)
                .containsEntry("chat_id", filter.hash("123456789"))
                .containsEntry("telegram_user_id", filter.hash("42"));
        assertThat(data.get("chat_id")).isNotEqualTo("123456789");
    }

    @Test
    void should_redact_string_values_in_breadcrumb_data_when_not_id_key() {
        // arrange
        var crumb = new Breadcrumb();
        crumb.setData("plant_name", "Монстера");
        crumb.setData("free_text", "произвольная заметка юзера");
        var event = new SentryEvent();
        event.addBreadcrumb(crumb);

        // act
        filter.sanitize(event);

        // assert
        // Любая строка в breadcrumb.data — потенциальный PII, даже под произвольным
        // ключом (не только FREE_TEXT_KEYS): код маскирует ВСЕ строки.
        Map<String, Object> data = event.getBreadcrumbs().get(0).getData();
        assertThat(data)
                .containsEntry("plant_name", SentryPiiFilter.REDACTED)
                .containsEntry("free_text", SentryPiiFilter.REDACTED);
    }

    @Test
    void should_keep_numeric_and_boolean_breadcrumb_data_when_sanitizing() {
        // arrange
        // числа/booleans (статусы, коды, длительности) НЕ PII — оставляем для диагностики.
        var crumb = new Breadcrumb();
        crumb.setData("status_code", 500);
        crumb.setData("duration_ms", 1234L);
        crumb.setData("retried", true);
        var event = new SentryEvent();
        event.addBreadcrumb(crumb);

        // act
        filter.sanitize(event);

        // assert
        Map<String, Object> data = event.getBreadcrumbs().get(0).getData();
        assertThat(data)
                .containsEntry("status_code", 500)
                .containsEntry("duration_ms", 1234L)
                .containsEntry("retried", true);
    }

    @Test
    void should_apply_all_data_rules_in_single_breadcrumb_when_mixed_values() {
        // arrange
        // один crumb со всеми тремя видами data + message: id → hash, string → redact,
        // number → kept.
        var crumb = new Breadcrumb();
        crumb.setMessage("handling callback delete:42");
        crumb.setData("chat_id", "999000");
        crumb.setData("callback_data", "delete:42");
        crumb.setData("attempt", 2);
        var event = new SentryEvent();
        event.addBreadcrumb(crumb);

        // act
        filter.sanitize(event);

        // assert
        Breadcrumb sanitized = event.getBreadcrumbs().get(0);
        assertThat(sanitized.getMessage()).isEqualTo(SentryPiiFilter.REDACTED);
        assertThat(sanitized.getData())
                .containsEntry("chat_id", filter.hash("999000"))
                .containsEntry("callback_data", SentryPiiFilter.REDACTED)
                .containsEntry("attempt", 2);
    }

    @Test
    void should_not_throw_when_event_has_no_breadcrumbs() {
        // arrange
        // SentryEvent без единого addBreadcrumb — getBreadcrumbs() пуст/нет крошек.
        var event = new SentryEvent();

        // act / assert
        assertThatNoException().isThrownBy(() -> filter.sanitize(event));
        assertThat(event.getBreadcrumbs()).isNullOrEmpty();
    }

    @Test
    void should_not_throw_when_breadcrumb_has_no_message_or_data() {
        // arrange
        var event = new SentryEvent();
        event.addBreadcrumb(new Breadcrumb());

        // act / assert
        assertThatNoException().isThrownBy(() -> filter.sanitize(event));
    }
}

package com.plantcare.core.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.context.support.StaticMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-тесты на {@link MessageService}.
 *
 * Поднимает минимальный Spring-контекст с реальным {@link ResourceBundleMessageSource},
 * настроенным на боевой basename {@code messages}. Это проверяет, что:
 *  - {@code messages_ru.properties} лежит в classpath и читается;
 *  - ключи резолвятся в локали {@code ru};
 *  - fallback на system locale выключен (отсутствующий ключ кидает {@link NoSuchMessageException}).
 *
 * Тест на интерполяцию аргументов использует отдельный экземпляр {@link MessageService},
 * собранный поверх {@link StaticMessageSource}, чтобы не засорять боевые properties
 * чисто тестовыми ключами.
 */
@SpringBootTest(classes = {MessageService.class, MessageServiceTest.TestConfig.class})
@DisplayName("Unit-тесты для MessageService (i18n wrapper)")
class MessageServiceTest {

    @Autowired
    private MessageService messageService;

    @TestConfiguration
    static class TestConfig {
        @Bean
        MessageSource messageSource() {
            ResourceBundleMessageSource source = new ResourceBundleMessageSource();
            source.setBasename("messages");
            source.setDefaultEncoding("UTF-8");
            source.setFallbackToSystemLocale(false);
            return source;
        }
    }

    @Test
    @DisplayName("Возвращает строку приветствия для command.start.greeting")
    void should_return_string_for_known_key_when_key_exists_in_properties() {
        String result = messageService.get("command.start.greeting");

        assertThat(result)
                .isNotBlank()
                .contains("Plants-care");
    }

    @Test
    @DisplayName("Возвращает справочный текст для command.help.text со списком команд")
    void should_return_help_text_when_help_key_requested() {
        String result = messageService.get("command.help.text");

        assertThat(result)
                .isNotBlank()
                .contains("/start")
                .contains("/menu")
                .contains("/help");
    }

    @Test
    @DisplayName("Перегрузка с chatId возвращает ту же строку, что и без chatId")
    void should_return_same_value_for_chatId_overload_when_locale_is_ignored() {
        String withoutChatId = messageService.get("command.start.greeting");
        String withChatId = messageService.get(12345L, "command.start.greeting");

        assertThat(withChatId).isEqualTo(withoutChatId);
    }

    @Test
    @DisplayName("Резолвит все 9 ключей из messages_ru.properties без исключений")
    void should_resolve_all_documented_keys_when_keys_exist_in_properties() {
        String[] keys = {
                "command.start.greeting",
                "command.help.text",
                "command.add.prompt",
                "command.add.button.my_templates",
                "command.add.button.search",
                "command.add.button.custom",
                "command.cancel.confirmation",
                "command.something_wrong.text",
                "command.unknown.text"
        };

        for (String key : keys) {
            assertThat(messageService.get(key))
                    .as("ключ %s должен быть в messages_ru.properties", key)
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("Бросает NoSuchMessageException, если ключа нет в properties")
    void should_throw_when_key_missing_in_properties() {
        assertThatThrownBy(() -> messageService.get("nonexistent.key.foo"))
                .isInstanceOf(NoSuchMessageException.class)
                .hasMessageContaining("nonexistent.key.foo");
    }

    @Test
    @DisplayName("Перегрузка с chatId также бросает NoSuchMessageException для отсутствующего ключа")
    void should_throw_when_key_missing_with_chatId_overload() {
        assertThatThrownBy(() -> messageService.get(42L, "another.missing.key"))
                .isInstanceOf(NoSuchMessageException.class)
                .hasMessageContaining("another.missing.key");
    }

    @Test
    @DisplayName("Интерполирует аргументы {0} и {1} в шаблоне")
    void should_interpolate_arguments_when_placeholders_present() {
        StaticMessageSource staticSource = new StaticMessageSource();
        staticSource.addMessage("test.placeholder.demo", Locale.of("ru"), "Привет, {0}! У тебя {1} растений.");
        MessageService localService = new MessageService(staticSource);

        String result = localService.get("test.placeholder.demo", "Антон", 5);

        assertThat(result).isEqualTo("Привет, Антон! У тебя 5 растений.");
    }

    @Test
    @DisplayName("Перегрузка get(chatId, key, args) также интерполирует аргументы")
    void should_interpolate_arguments_when_called_via_chatId_overload() {
        StaticMessageSource staticSource = new StaticMessageSource();
        staticSource.addMessage("test.placeholder.demo", Locale.of("ru"), "У {0} {1} полива(ов)");
        MessageService localService = new MessageService(staticSource);

        String result = localService.get(999L, "test.placeholder.demo", "фикуса", 3);

        assertThat(result).isEqualTo("У фикуса 3 полива(ов)");
    }

    @Test
    @DisplayName("Корректно декодирует UTF-8 кириллицу из messages_ru.properties")
    void should_decode_cyrillic_correctly_when_utf8_encoding_configured() {
        String result = messageService.get("command.cancel.confirmation");

        assertThat(result)
                .isNotBlank()
                .contains("Действие отменено")
                .doesNotContain("?")
                .doesNotContain("�");
    }
}

package com.plantcare.bot.service;

import com.plantcare.core.domain.User;
import com.plantcare.core.service.TransplantSuggestionService;
import com.plantcare.core.service.TransplantSuggestionService.ReactionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты для {@link TransplantSuggestionCallbackService} (issue #141):
 * callback'и {@code TRANSPLANT_SUGGEST:ADD:{id}} / {@code TRANSPLANT_SUGGEST:SKIP:{id}}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для TransplantSuggestionCallbackService")
class TransplantSuggestionCallbackServiceTest {

    @Mock private TransplantSuggestionService suggestionService;
    @Mock private TelegramClient telegramClient;
    @Mock private CallbackQuery callbackQuery;
    @Mock private Message message;

    @InjectMocks
    private TransplantSuggestionCallbackService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().telegramChatId(300L).build();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", 42L);

        lenient().when(callbackQuery.getId()).thenReturn("cb-1");
        lenient().when(callbackQuery.getMessage()).thenReturn(message);
        lenient().when(message.getChatId()).thenReturn(300L);
        lenient().when(message.getMessageId()).thenReturn(88);
    }

    @Test
    @DisplayName("ADD с результатом OK — редактирует сообщение с ярлыком расходников и отвечает «Готово!»")
    void should_edit_message_and_answer_done_when_add_returns_ok() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("TRANSPLANT_SUGGEST:ADD:7");
        when(suggestionService.addToShoppingList(42L, 7L)).thenReturn(ReactionResult.OK);
        when(suggestionService.suppliesLabel()).thenReturn("грунт и дренаж");

        service.handleCallback(callbackQuery, telegramClient, user);

        ArgumentCaptor<EditMessageText> editCaptor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(editCaptor.capture());
        assertThat(editCaptor.getValue().getChatId()).isEqualTo("300");
        assertThat(editCaptor.getValue().getMessageId()).isEqualTo(88);
        assertThat(editCaptor.getValue().getText())
                .isEqualTo("✅ Добавил в список покупок: грунт и дренаж");
        assertThat(editCaptor.getValue().getReplyMarkup()).isNull();
    }

    @Test
    @DisplayName("ADD с результатом ALREADY_HANDLED — сообщение «уже добавлено», алёрт «Уже обработано»")
    void should_show_already_added_when_add_returns_already_handled() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("TRANSPLANT_SUGGEST:ADD:7");
        when(suggestionService.addToShoppingList(42L, 7L)).thenReturn(ReactionResult.ALREADY_HANDLED);

        service.handleCallback(callbackQuery, telegramClient, user);

        ArgumentCaptor<EditMessageText> editCaptor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(editCaptor.capture());
        assertThat(editCaptor.getValue().getText()).isEqualTo("✅ Уже добавлено в список покупок");
    }

    @Test
    @DisplayName("ADD с результатом NOT_FOUND — только алёрт об ошибке, сообщение не редактируется")
    void should_answer_not_found_alert_without_editing_message_when_add_not_found() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("TRANSPLANT_SUGGEST:ADD:7");
        when(suggestionService.addToShoppingList(42L, 7L)).thenReturn(ReactionResult.NOT_FOUND);

        service.handleCallback(callbackQuery, telegramClient, user);

        verify(telegramClient, never()).execute(org.mockito.ArgumentMatchers.any(EditMessageText.class));

        ArgumentCaptor<AnswerCallbackQuery> answerCaptor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(answerCaptor.capture());
        assertThat(answerCaptor.getValue().getText()).isEqualTo("❌ Подсказка не найдена");
    }

    @Test
    @DisplayName("SKIP с результатом OK — редактирует сообщение «не буду напоминать», отвечает «Готово»")
    void should_edit_message_and_answer_done_when_skip_returns_ok() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("TRANSPLANT_SUGGEST:SKIP:9");
        when(suggestionService.dismiss(42L, 9L)).thenReturn(ReactionResult.OK);

        service.handleCallback(callbackQuery, telegramClient, user);

        ArgumentCaptor<EditMessageText> editCaptor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(editCaptor.capture());
        assertThat(editCaptor.getValue().getText()).isEqualTo("Хорошо, не буду напоминать 👌");

        ArgumentCaptor<AnswerCallbackQuery> answerCaptor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(answerCaptor.capture());
        assertThat(answerCaptor.getValue().getText()).isEqualTo("Готово");
    }

    @Test
    @DisplayName("SKIP с результатом ALREADY_HANDLED — то же сообщение, алёрт «Уже обработано»")
    void should_answer_already_handled_when_skip_returns_already_handled() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("TRANSPLANT_SUGGEST:SKIP:9");
        when(suggestionService.dismiss(42L, 9L)).thenReturn(ReactionResult.ALREADY_HANDLED);

        service.handleCallback(callbackQuery, telegramClient, user);

        ArgumentCaptor<AnswerCallbackQuery> answerCaptor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(answerCaptor.capture());
        assertThat(answerCaptor.getValue().getText()).isEqualTo("Уже обработано");
    }

    @Test
    @DisplayName("SKIP с результатом NOT_FOUND — только алёрт об ошибке")
    void should_answer_not_found_alert_when_skip_not_found() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("TRANSPLANT_SUGGEST:SKIP:9");
        when(suggestionService.dismiss(42L, 9L)).thenReturn(ReactionResult.NOT_FOUND);

        service.handleCallback(callbackQuery, telegramClient, user);

        verify(telegramClient, never()).execute(org.mockito.ArgumentMatchers.any(EditMessageText.class));
        ArgumentCaptor<AnswerCallbackQuery> answerCaptor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(answerCaptor.capture());
        assertThat(answerCaptor.getValue().getText()).isEqualTo("❌ Подсказка не найдена");
    }

    @Test
    @DisplayName("Неизвестное действие (не ADD/SKIP) — алёрт «Неизвестное действие», сервис не вызывается")
    void should_answer_unknown_action_alert_for_unrecognized_action() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("TRANSPLANT_SUGGEST:FOO:9");

        service.handleCallback(callbackQuery, telegramClient, user);

        verify(suggestionService, never()).addToShoppingList(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(suggestionService, never()).dismiss(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        ArgumentCaptor<AnswerCallbackQuery> answerCaptor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(answerCaptor.capture());
        assertThat(answerCaptor.getValue().getText()).isEqualTo("❌ Неизвестное действие");
    }

    @Test
    @DisplayName("Битый ID (не число) — алёрт «Неверный ID», сервис не вызывается")
    void should_answer_invalid_id_alert_when_id_not_parseable() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("TRANSPLANT_SUGGEST:ADD:not-a-number");

        service.handleCallback(callbackQuery, telegramClient, user);

        verify(suggestionService, never()).addToShoppingList(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        ArgumentCaptor<AnswerCallbackQuery> answerCaptor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(answerCaptor.capture());
        assertThat(answerCaptor.getValue().getText()).isEqualTo("❌ Неверный ID");
    }

    @Test
    @DisplayName("Данные с неверным числом частей — алёрт «Неизвестная команда»")
    void should_answer_unknown_command_alert_when_data_has_wrong_number_of_parts() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("TRANSPLANT_SUGGEST:ADD");

        service.handleCallback(callbackQuery, telegramClient, user);

        ArgumentCaptor<AnswerCallbackQuery> answerCaptor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(answerCaptor.capture());
        assertThat(answerCaptor.getValue().getText()).isEqualTo("❌ Неизвестная команда");
    }

    @Test
    @DisplayName("TelegramApiException при редактировании сообщения гасится, не долетает до вызывающего кода")
    void should_swallow_telegram_exception_on_edit_message() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("TRANSPLANT_SUGGEST:ADD:7");
        when(suggestionService.addToShoppingList(42L, 7L)).thenReturn(ReactionResult.OK);
        when(suggestionService.suppliesLabel()).thenReturn("грунт");
        when(telegramClient.execute(org.mockito.ArgumentMatchers.any(EditMessageText.class)))
                .thenThrow(new TelegramApiException("network error"));

        service.handleCallback(callbackQuery, telegramClient, user);

        // answerCallback всё равно должен быть вызван — исключение изолировано в editMessage.
        ArgumentCaptor<AnswerCallbackQuery> answerCaptor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(answerCaptor.capture());
        assertThat(answerCaptor.getValue().getText()).isEqualTo("Готово!");
    }

    @Test
    @DisplayName("TelegramApiException при ответе на callback гасится, не долетает до вызывающего кода")
    void should_swallow_telegram_exception_on_answer_callback() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("TRANSPLANT_SUGGEST:FOO:9");
        when(telegramClient.execute(org.mockito.ArgumentMatchers.any(AnswerCallbackQuery.class)))
                .thenThrow(new TelegramApiException("network error"));

        service.handleCallback(callbackQuery, telegramClient, user);

        verify(telegramClient).execute(org.mockito.ArgumentMatchers.any(AnswerCallbackQuery.class));
    }
}

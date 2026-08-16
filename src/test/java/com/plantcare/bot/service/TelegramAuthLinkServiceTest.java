package com.plantcare.bot.service;

import com.plantcare.api.auth.service.TelegramAuthService;
import com.plantcare.core.service.MessageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты TelegramAuthLinkService (бот-сторона входа, issue #318)")
class TelegramAuthLinkServiceTest {

    @Mock
    private TelegramAuthService telegramAuthService;

    @Mock
    private MessageService messageService;

    @Mock
    private TelegramClient telegramClient;

    @InjectMocks
    private TelegramAuthLinkService service;

    @Test
    @DisplayName("extractSessionId достаёт sessionId из payload auth_<sessionId>")
    void shouldExtractSessionId() {
        assertThat(service.extractSessionId("auth_abc123")).isEqualTo("abc123");
    }

    @Test
    @DisplayName("extractSessionId возвращает null для не-auth payload и пустого sessionId")
    void shouldReturnNullForNonAuthPayload() {
        assertThat(service.extractSessionId("invite_xyz")).isNull();
        assertThat(service.extractSessionId("auth_")).isNull();
        assertThat(service.extractSessionId(null)).isNull();
    }

    @Test
    @DisplayName("handleAuthLink привязывает код по chat_id ИЗ update и шлёт его в чат")
    void shouldBindCodeFromUpdateChatIdAndSendIt() throws Exception {
        Long chatId = 555L;
        when(telegramAuthService.bindCode("sess1", chatId)).thenReturn(Optional.of("123456"));
        when(messageService.get(eq(chatId), eq("command.start.auth_code"), eq("123456")))
                .thenReturn("code text 123456");

        service.handleAuthLink("sess1", chatId, telegramClient);

        // chat_id, по которому привязали код, — именно тот, что пришёл из update
        verify(telegramAuthService).bindCode("sess1", chatId);
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getChatId()).isEqualTo(chatId.toString());
        assertThat(captor.getValue().getText()).contains("123456");
    }

    @Test
    @DisplayName("handleAuthLink на истёкшей/несуществующей сессии шлёт сообщение об устаревшей ссылке")
    void shouldSendExpiredMessageWhenSessionMissing() throws Exception {
        Long chatId = 555L;
        when(telegramAuthService.bindCode("sessX", chatId)).thenReturn(Optional.empty());
        when(messageService.get(eq(chatId), eq("command.start.auth_expired")))
                .thenReturn("expired text");

        service.handleAuthLink("sessX", chatId, telegramClient);

        verify(messageService).get(eq(chatId), eq("command.start.auth_expired"));
        verify(messageService, never()).get(anyString(), eq("command.start.auth_code"), any());
        verify(telegramClient).execute(any(SendMessage.class));
    }
}

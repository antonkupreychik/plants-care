package com.plantcare.bot.weather.service;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.core.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для WeatherMenuService")
class WeatherMenuServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserService userService;

    @Mock
    private TelegramClient telegramClient;

    @InjectMocks
    private WeatherMenuService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(777L)
                .username("weather_user")
                .build();
    }

    // ---------------------------------------------------------------
    // sendWeatherScreen
    // ---------------------------------------------------------------

    @Test
    @DisplayName("sendWeatherScreen(messageId) редактирует сообщение и показывает выключенный статус без локации")
    void shouldEditScreenShowingDisabledAndNoLocation() throws TelegramApiException {
        service.sendWeatherScreen(user, 10, telegramClient);

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(captor.capture());
        verify(telegramClient, never()).execute(any(SendMessage.class));

        EditMessageText edit = captor.getValue();
        assertThat(edit.getChatId()).isEqualTo("777");
        assertThat(edit.getMessageId()).isEqualTo(10);
        assertThat(edit.getText()).contains("⏸ Выключено");
        assertThat(edit.getText()).contains("⚠️ не задана");
        assertThat(edit.getReplyMarkup()).isInstanceOf(InlineKeyboardMarkup.class);
    }

    @Test
    @DisplayName("sendWeatherScreen(client) без messageId шлёт новое сообщение")
    void shouldSendNewMessageWhenNoMessageIdOverload() throws TelegramApiException {
        service.sendWeatherScreen(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        verify(telegramClient, never()).execute(any(EditMessageText.class));

        assertThat(captor.getValue().getChatId()).isEqualTo("777");
    }

    @Test
    @DisplayName("Если редактирование падает — фолбэк на новое сообщение")
    void shouldFallBackToNewMessageWhenEditFails() throws TelegramApiException {
        when(telegramClient.execute(any(EditMessageText.class)))
                .thenThrow(new TelegramApiException("edit failed"));

        service.sendWeatherScreen(user, 55, telegramClient);

        verify(telegramClient).execute(any(EditMessageText.class));
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("Если и отправка нового сообщения падает — исключение не прокидывается наружу")
    void shouldNotThrowWhenSendAlsoFailsAfterEditFailure() throws TelegramApiException {
        when(telegramClient.execute(any(EditMessageText.class)))
                .thenThrow(new TelegramApiException("edit failed"));
        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiException("send failed too"));

        service.sendWeatherScreen(user, 55, telegramClient);

        verify(telegramClient).execute(any(EditMessageText.class));
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("Показывает координаты локации и статус ✅ включено, когда погода usable")
    void shouldShowLocationCoordinatesAndUsableHint() throws TelegramApiException {
        user.setWeatherEnabled(true);
        user.setWeatherLat(53.9006);
        user.setWeatherLon(27.5590);

        service.sendWeatherScreen(user, null, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        String text = captor.getValue().getText();
        assertThat(text).contains("✅ Включено");
        assertThat(text).contains(String.format("%.2f, %.2f", 53.9006, 27.5590));
        assertThat(text).contains("Бот будет добавлять строку про влажность");
    }

    @Test
    @DisplayName("Если включено, но локация не задана — просит указать локацию")
    void shouldPromptToSetLocationWhenEnabledButNoLocation() throws TelegramApiException {
        user.setWeatherEnabled(true);

        service.sendWeatherScreen(user, null, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText()).contains("Указать локацию");
    }

    @Test
    @DisplayName("Если выключено, но локация задана — говорит что подсказки выключены")
    void shouldNoteHintsDisabledWhenLocationSetButDisabled() throws TelegramApiException {
        user.setWeatherEnabled(false);
        user.setWeatherLat(1.0);
        user.setWeatherLon(1.0);

        service.sendWeatherScreen(user, null, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Локация сохранена, но подсказки сейчас выключены");
    }

    @Test
    @DisplayName("Кнопка локации: 'Изменить локацию' когда уже задана")
    void shouldShowChangeLocationButtonWhenLocationAlreadySet() throws TelegramApiException {
        user.setWeatherLat(1.0);
        user.setWeatherLon(1.0);

        service.sendWeatherScreen(user, null, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
        assertThat(markup.getKeyboard().stream()
                .flatMap(row -> row.stream())
                .anyMatch(btn -> "📍 Изменить локацию".equals(btn.getText())))
                .isTrue();
    }

    // ---------------------------------------------------------------
    // toggleEnabled
    // ---------------------------------------------------------------

    @Test
    @DisplayName("toggleEnabled включает погоду, если была выключена, и сохраняет юзера")
    void shouldEnableWeatherWhenCurrentlyDisabled() throws TelegramApiException {
        user.setWeatherEnabled(false);

        service.toggleEnabled(user, 20, telegramClient);

        assertThat(user.isWeatherEnabled()).isTrue();
        verify(userRepository).save(user);
        verify(telegramClient).execute(any(EditMessageText.class));
    }

    @Test
    @DisplayName("toggleEnabled выключает погоду, если была включена")
    void shouldDisableWeatherWhenCurrentlyEnabled() throws TelegramApiException {
        user.setWeatherEnabled(true);

        service.toggleEnabled(user, 20, telegramClient);

        assertThat(user.isWeatherEnabled()).isFalse();
        verify(userRepository).save(user);
    }

    // ---------------------------------------------------------------
    // promptForLocation
    // ---------------------------------------------------------------

    @Test
    @DisplayName("promptForLocation переводит в AWAITING_WEATHER_LOCATION и шлёт клавиатуру с requestLocation")
    void shouldPromptForLocationAndSendReplyKeyboard() throws TelegramApiException {
        service.promptForLocation(user, telegramClient);

        verify(userService).updateState(user, ConversationState.AWAITING_WEATHER_LOCATION);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        SendMessage sent = captor.getValue();
        assertThat(sent.getChatId()).isEqualTo("777");
        assertThat(sent.getText()).contains("Open-Meteo");
        assertThat(sent.getReplyMarkup()).isInstanceOf(ReplyKeyboardMarkup.class);

        ReplyKeyboardMarkup keyboard = (ReplyKeyboardMarkup) sent.getReplyMarkup();
        assertThat(keyboard.getKeyboard()).hasSize(2);
        assertThat(keyboard.getKeyboard().get(0).get(0).getText())
                .isEqualTo("📍 Отправить локацию");
        assertThat(keyboard.getKeyboard().get(0).get(0).getRequestLocation()).isTrue();
        assertThat(keyboard.getKeyboard().get(1).get(0).getText()).isEqualTo("❌ Отмена");
    }

    @Test
    @DisplayName("promptForLocation не бросает исключение, если отправка падает")
    void shouldNotThrowWhenPromptSendFails() throws TelegramApiException {
        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiException("boom"));

        service.promptForLocation(user, telegramClient);

        verify(userService).updateState(user, ConversationState.AWAITING_WEATHER_LOCATION);
        verify(telegramClient).execute(any(SendMessage.class));
    }
}

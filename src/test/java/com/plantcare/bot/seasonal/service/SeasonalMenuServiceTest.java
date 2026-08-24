package com.plantcare.bot.seasonal.service;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.Season;
import com.plantcare.core.domain.enums.SeasonalMode;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.core.seasonal.service.SeasonResolver;
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
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для SeasonalMenuService")
class SeasonalMenuServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private SeasonResolver seasonResolver;

    @Mock
    private TelegramClient telegramClient;

    @InjectMocks
    private SeasonalMenuService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(555L)
                .username("plant_lover")
                .build();
    }

    // ---------------------------------------------------------------
    // sendScreen / rendering
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Отправляет новое сообщение при messageId=null и режиме MULTIPLIER летом")
    void shouldSendNewMessageWithMultiplierTextWhenMessageIdIsNull() throws TelegramApiException {
        lenient().when(seasonResolver.currentSeason(user)).thenReturn(Season.SUMMER);

        service.sendScreen(user, null, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        verify(telegramClient, never()).execute(any(EditMessageText.class));

        SendMessage sent = captor.getValue();
        assertThat(sent.getChatId()).isEqualTo("555");
        assertThat(sent.getText()).contains("Сейчас сезон: лето");
        assertThat(sent.getText()).contains("по коэффициенту");
        assertThat(sent.getText()).contains("⏸ Выключено");
        assertThat(sent.getReplyMarkup()).isInstanceOf(InlineKeyboardMarkup.class);
    }

    @Test
    @DisplayName("Редактирует существующее сообщение при заданном messageId")
    void shouldEditExistingMessageWhenMessageIdProvided() throws TelegramApiException {
        lenient().when(seasonResolver.currentSeason(user)).thenReturn(Season.WINTER);

        service.sendScreen(user, 77, telegramClient);

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(captor.capture());
        verify(telegramClient, never()).execute(any(SendMessage.class));

        EditMessageText edit = captor.getValue();
        assertThat(edit.getChatId()).isEqualTo("555");
        assertThat(edit.getMessageId()).isEqualTo(77);
        assertThat(edit.getText()).contains("Сейчас сезон: зима");
    }

    @Test
    @DisplayName("Если редактирование падает — шлёт новое сообщение вместо него")
    void shouldFallBackToNewMessageWhenEditFails() throws TelegramApiException {
        lenient().when(seasonResolver.currentSeason(user)).thenReturn(Season.SUMMER);
        when(telegramClient.execute(any(EditMessageText.class)))
                .thenThrow(new TelegramApiException("edit failed"));

        service.sendScreen(user, 99, telegramClient);

        verify(telegramClient).execute(any(EditMessageText.class));
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("Если и отправка нового сообщения падает — исключение не прокидывается наружу")
    void shouldNotThrowWhenSendMessageFailsAfterEditFailure() throws TelegramApiException {
        lenient().when(seasonResolver.currentSeason(user)).thenReturn(Season.SUMMER);
        when(telegramClient.execute(any(EditMessageText.class)))
                .thenThrow(new TelegramApiException("edit failed"));
        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiException("send failed too"));

        service.sendScreen(user, 99, telegramClient);

        verify(telegramClient).execute(any(EditMessageText.class));
        verify(telegramClient).execute(any(SendMessage.class));
    }

    // ---------------------------------------------------------------
    // toggleEnabled
    // ---------------------------------------------------------------

    @Test
    @DisplayName("toggleEnabled включает сезонность, если она была выключена")
    void shouldEnableSeasonalWhenCurrentlyDisabled() throws TelegramApiException {
        user.setSeasonalEnabled(false);
        lenient().when(seasonResolver.currentSeason(user)).thenReturn(Season.SUMMER);

        service.toggleEnabled(user, null, telegramClient);

        assertThat(user.isSeasonalEnabled()).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("toggleEnabled выключает сезонность, если она была включена")
    void shouldDisableSeasonalWhenCurrentlyEnabled() throws TelegramApiException {
        user.setSeasonalEnabled(true);
        lenient().when(seasonResolver.currentSeason(user)).thenReturn(Season.SUMMER);

        service.toggleEnabled(user, null, telegramClient);

        assertThat(user.isSeasonalEnabled()).isFalse();
        verify(userRepository).save(user);
    }

    // ---------------------------------------------------------------
    // setMode
    // ---------------------------------------------------------------

    @Test
    @DisplayName("setMode(FIXED) переключает режим и рендерит фиксированные интервалы")
    void shouldSwitchModeToFixed() throws TelegramApiException {
        user.setSeasonalMode(SeasonalMode.MULTIPLIER);
        lenient().when(seasonResolver.currentSeason(user)).thenReturn(Season.SUMMER);

        service.setMode(user, SeasonalMode.FIXED, null, telegramClient);

        assertThat(user.getSeasonalMode()).isEqualTo(SeasonalMode.FIXED);
        verify(userRepository).save(user);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("фиксированные интервалы");
    }

    @Test
    @DisplayName("setMode(MULTIPLIER) переключает режим и рендерит коэффициенты")
    void shouldSwitchModeToMultiplier() throws TelegramApiException {
        user.setSeasonalMode(SeasonalMode.FIXED);
        lenient().when(seasonResolver.currentSeason(user)).thenReturn(Season.WINTER);

        service.setMode(user, SeasonalMode.MULTIPLIER, null, telegramClient);

        assertThat(user.getSeasonalMode()).isEqualTo(SeasonalMode.MULTIPLIER);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("по коэффициенту");
    }

    // ---------------------------------------------------------------
    // cycleMultiplier
    // ---------------------------------------------------------------

    @Test
    @DisplayName("cycleMultiplier(SUMMER) сдвигает летний коэффициент на следующий шаг")
    void shouldCycleSummerMultiplierToNextStep() throws TelegramApiException {
        user.setSummerMultiplier(new BigDecimal("0.80"));
        lenient().when(seasonResolver.currentSeason(user)).thenReturn(Season.SUMMER);

        service.cycleMultiplier(user, Season.SUMMER, null, telegramClient);

        assertThat(user.getSummerMultiplier()).isEqualByComparingTo("0.90");
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("cycleMultiplier(WINTER) сдвигает зимний коэффициент на следующий шаг")
    void shouldCycleWinterMultiplierToNextStep() throws TelegramApiException {
        user.setWinterMultiplier(new BigDecimal("1.20"));
        lenient().when(seasonResolver.currentSeason(user)).thenReturn(Season.WINTER);

        service.cycleMultiplier(user, Season.WINTER, null, telegramClient);

        assertThat(user.getWinterMultiplier()).isEqualByComparingTo("1.30");
    }

    @Test
    @DisplayName("cycleMultiplier заворачивает по кругу с максимума на минимум")
    void shouldWrapMultiplierFromMaxToMin() throws TelegramApiException {
        user.setSummerMultiplier(new BigDecimal("1.50"));
        lenient().when(seasonResolver.currentSeason(user)).thenReturn(Season.SUMMER);

        service.cycleMultiplier(user, Season.SUMMER, null, telegramClient);

        assertThat(user.getSummerMultiplier()).isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("cycleMultiplier с незнакомым значением коэффициента откатывается на первый шаг")
    void shouldResetToFirstStepWhenCurrentMultiplierIsUnknown() throws TelegramApiException {
        user.setSummerMultiplier(new BigDecimal("3.33"));
        lenient().when(seasonResolver.currentSeason(user)).thenReturn(Season.SUMMER);

        service.cycleMultiplier(user, Season.SUMMER, null, telegramClient);

        assertThat(user.getSummerMultiplier()).isEqualByComparingTo("0.50");
    }

    // ---------------------------------------------------------------
    // cycleInterval
    // ---------------------------------------------------------------

    @Test
    @DisplayName("cycleInterval(SUMMER) с незаданным интервалом стартует от базы 7 и берёт следующий шаг")
    void shouldCycleSummerIntervalFromDefaultWhenUnset() throws TelegramApiException {
        user.setSummerIntervalOverrideDays(null);
        lenient().when(seasonResolver.currentSeason(user)).thenReturn(Season.SUMMER);

        service.cycleInterval(user, Season.SUMMER, null, telegramClient);

        assertThat(user.getSummerIntervalOverrideDays()).isEqualTo(10);
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("cycleInterval(WINTER) сдвигает зимний интервал на следующий шаг из списка")
    void shouldCycleWinterIntervalToNextStep() throws TelegramApiException {
        user.setWinterIntervalOverrideDays(14);
        lenient().when(seasonResolver.currentSeason(user)).thenReturn(Season.WINTER);

        service.cycleInterval(user, Season.WINTER, null, telegramClient);

        assertThat(user.getWinterIntervalOverrideDays()).isEqualTo(21);
    }

    @Test
    @DisplayName("cycleInterval заворачивает с максимума (60) на минимум (1)")
    void shouldWrapIntervalFromMaxToMin() throws TelegramApiException {
        user.setSummerIntervalOverrideDays(60);
        lenient().when(seasonResolver.currentSeason(user)).thenReturn(Season.SUMMER);

        service.cycleInterval(user, Season.SUMMER, null, telegramClient);

        assertThat(user.getSummerIntervalOverrideDays()).isEqualTo(1);
    }

    @Test
    @DisplayName("cycleInterval с незнакомым значением интервала откатывается на первый шаг")
    void shouldResetIntervalToFirstStepWhenCurrentIntervalIsUnknown() throws TelegramApiException {
        user.setSummerIntervalOverrideDays(99);
        lenient().when(seasonResolver.currentSeason(user)).thenReturn(Season.SUMMER);

        service.cycleInterval(user, Season.SUMMER, null, telegramClient);

        assertThat(user.getSummerIntervalOverrideDays()).isEqualTo(1);
    }

    @Test
    @DisplayName("cycleMultiplier с explicit null-коэффициентом стартует с первого шага")
    void shouldStartMultiplierFromFirstStepWhenCurrentIsNull() throws TelegramApiException {
        user.setSummerMultiplier(null);
        lenient().when(seasonResolver.currentSeason(user)).thenReturn(Season.SUMMER);

        service.cycleMultiplier(user, Season.SUMMER, null, telegramClient);

        assertThat(user.getSummerMultiplier()).isEqualByComparingTo("0.50");
    }

    // ---------------------------------------------------------------
    // clearInterval
    // ---------------------------------------------------------------

    @Test
    @DisplayName("clearInterval(SUMMER) сбрасывает override в null")
    void shouldClearSummerIntervalOverride() throws TelegramApiException {
        user.setSummerIntervalOverrideDays(21);
        lenient().when(seasonResolver.currentSeason(user)).thenReturn(Season.SUMMER);

        service.clearInterval(user, Season.SUMMER, null, telegramClient);

        assertThat(user.getSummerIntervalOverrideDays()).isNull();
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("clearInterval(WINTER) сбрасывает override в null")
    void shouldClearWinterIntervalOverride() throws TelegramApiException {
        user.setWinterIntervalOverrideDays(21);
        lenient().when(seasonResolver.currentSeason(user)).thenReturn(Season.WINTER);

        service.clearInterval(user, Season.WINTER, null, telegramClient);

        assertThat(user.getWinterIntervalOverrideDays()).isNull();
    }

    // ---------------------------------------------------------------
    // buildText: mode-dependent formatting + null-interval formatting
    // ---------------------------------------------------------------

    @Test
    @DisplayName("В режиме FIXED незаданный интервал показывается как «не задан»")
    void shouldShowNotSetLabelForNullFixedIntervals() throws TelegramApiException {
        user.setSeasonalMode(SeasonalMode.FIXED);
        user.setSummerIntervalOverrideDays(null);
        user.setWinterIntervalOverrideDays(null);
        lenient().when(seasonResolver.currentSeason(user)).thenReturn(Season.SUMMER);

        service.sendScreen(user, null, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("не задан");
    }

    @Test
    @DisplayName("В режиме FIXED заданный интервал показывается в днях")
    void shouldShowIntervalInDaysWhenFixedIntervalSet() throws TelegramApiException {
        user.setSeasonalMode(SeasonalMode.FIXED);
        user.setSummerIntervalOverrideDays(14);
        lenient().when(seasonResolver.currentSeason(user)).thenReturn(Season.SUMMER);

        service.sendScreen(user, null, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("14 дн.");
    }

    @Test
    @DisplayName("Границы сезонов форматируются как dd.MM из MMDD")
    void shouldFormatSeasonBoundariesAsDdMm() throws TelegramApiException {
        user.setSummerStartMmdd(401);
        user.setWinterStartMmdd(1015);
        lenient().when(seasonResolver.currentSeason(user)).thenReturn(Season.SUMMER);

        service.sendScreen(user, null, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("01.04");
        assertThat(captor.getValue().getText()).contains("15.10");
    }
}

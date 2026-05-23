package com.plantcare.bot.service;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.repository.CareScheduleRepository;
import com.plantcare.bot.repository.PlantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты на vacation-баннер в главном меню (issue #53).
 *
 * Стандартный паттерн как в {@link MainMenuServiceTest}: моки, без Spring-контекста.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MainMenuService — vacation banner (issue #53)")
class MainMenuServiceVacationBannerTest {

    @Mock private PlantRepository plantRepository;
    @Mock private CareScheduleRepository careScheduleRepository;
    @Mock private LocationService locationService;
    @Mock private CareHistoryService careHistoryService;
    @Mock private TelegramClient telegramClient;

    private MainMenuService service;

    @BeforeEach
    void setUp() {
        service = new MainMenuService(
                plantRepository,
                careScheduleRepository,
                locationService,
                careHistoryService
        );

        lenient().when(careHistoryService.computeUserStreak(any(), any())).thenReturn(0);
        lenient().when(plantRepository.countByUserIdAndArchivedAtIsNull(any())).thenReturn(0L);
        lenient().when(careScheduleRepository.findUserSchedulesDueBefore(any(), any()))
                .thenReturn(List.of());
        lenient().when(locationService.getUserLocations(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("Юзер в отпуске → баннер «🏖 Отпуск до …» + кнопка «Вернуться сейчас»")
    void should_prepend_vacation_banner_when_user_paused() throws TelegramApiException {
        // paused_until далеко в будущем, чтобы user.isPaused() == true.
        LocalDateTime pausedUntil = LocalDateTime.now().plusDays(7);

        User user = User.builder()
                .telegramChatId(123L)
                .timezone("Europe/Riga")
                .pausedUntil(pausedUntil)
                .build();

        service.sendMainMenu(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        SendMessage sent = captor.getValue();
        assertThat(sent.getText())
                .contains("🏖")
                .contains("Отпуск до");

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sent.getReplyMarkup();
        List<String> callbacks = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();

        assertThat(callbacks).contains("MENU:VACATION_END");
    }

    @Test
    @DisplayName("Юзер не в отпуске → баннер не показывается, кнопки VACATION_END нет")
    void should_not_show_banner_when_user_not_paused() throws TelegramApiException {
        User user = User.builder()
                .telegramChatId(124L)
                .timezone("Europe/Riga")
                .pausedUntil(null)
                .build();

        service.sendMainMenu(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        SendMessage sent = captor.getValue();
        assertThat(sent.getText()).doesNotContain("Отпуск до");

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sent.getReplyMarkup();
        List<String> callbacks = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();

        assertThat(callbacks).doesNotContain("MENU:VACATION_END");
    }

    @Test
    @DisplayName("Юзер с просроченным paused_until (в прошлом) → баннер не показывается")
    void should_not_show_banner_when_paused_until_in_past() throws TelegramApiException {
        User user = User.builder()
                .telegramChatId(125L)
                .timezone("Europe/Riga")
                .pausedUntil(LocalDateTime.now().minusHours(1))
                .build();

        service.sendMainMenu(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText()).doesNotContain("Отпуск до");
    }
}

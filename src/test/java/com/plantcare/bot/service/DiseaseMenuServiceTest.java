package com.plantcare.bot.service;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.service.DiseaseCard;
import com.plantcare.core.service.DiseaseNotFoundException;
import com.plantcare.core.service.DiseaseService;
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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для DiseaseMenuService (issue #140)")
class DiseaseMenuServiceTest {

    @Mock
    private DiseaseService diseaseService;

    @Mock
    private UserService userService;

    @Mock
    private TelegramClient telegramClient;

    @InjectMocks
    private DiseaseMenuService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(100L)
                .timezone("UTC")
                .build();
    }

    private List<String> callbackDataOf(InlineKeyboardMarkup keyboard) {
        return keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
    }

    private List<String> buttonTextsOf(InlineKeyboardMarkup keyboard) {
        return keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getText)
                .toList();
    }

    @Test
    @DisplayName("sendList сбрасывает state и шлёт список болезней с кнопкой поиска")
    void should_resetStateAndSendListWithSearchButton_when_diseasesExist() throws TelegramApiException {
        DiseaseCard aphid = new DiseaseCard(1L, "Тля", "Aphidoidea", "симптомы", "лечение", "профилактика");
        DiseaseCard rot = new DiseaseCard(2L, "Гниль корней", null, "симптомы2", "лечение2", "профилактика2");
        when(diseaseService.getAll()).thenReturn(List.of(aphid, rot));

        service.sendList(user, telegramClient);

        verify(userService).resetToIdle(user);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        SendMessage sent = captor.getValue();

        assertThat(sent.getChatId()).isEqualTo("100");
        assertThat(sent.getText()).contains("Болезни и вредители");
        assertThat(sent.getParseMode()).isEqualTo("Markdown");

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sent.getReplyMarkup();
        assertThat(buttonTextsOf(keyboard)).contains("🔍 Найти по симптому", "Тля", "Гниль корней");
        assertThat(callbackDataOf(keyboard)).contains(
                DiseaseMenuService.SEARCH_CALLBACK,
                DiseaseMenuService.VIEW_PREFIX + "1",
                DiseaseMenuService.VIEW_PREFIX + "2"
        );
    }

    @Test
    @DisplayName("sendList с пустым справочником — только кнопка поиска")
    void should_sendOnlySearchButton_when_diseaseListEmpty() throws TelegramApiException {
        when(diseaseService.getAll()).thenReturn(List.of());

        service.sendList(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
        assertThat(keyboard.getKeyboard()).hasSize(1);
        assertThat(callbackDataOf(keyboard)).containsExactly(DiseaseMenuService.SEARCH_CALLBACK);
    }

    @Test
    @DisplayName("startSearch переводит пользователя в AWAITING_DISEASE_SEARCH и просит ввести симптом")
    void should_switchToAwaitingSearchState_when_startSearchCalled() throws TelegramApiException {
        service.startSearch(user, telegramClient);

        verify(userService).updateState(user, ConversationState.AWAITING_DISEASE_SEARCH);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Напиши симптом или название проблемы");
        assertThat(captor.getValue().getParseMode()).isNull();
    }

    @Test
    @DisplayName("sendSearchResults с пустым результатом — предлагает открыть полный список")
    void should_offerFullList_when_searchResultsEmpty() throws TelegramApiException {
        when(diseaseService.search("паутинка", 10)).thenReturn(List.of());

        service.sendSearchResults(user, "паутинка", telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        SendMessage sent = captor.getValue();

        assertThat(sent.getText()).contains("Ничего не нашёл по запросу «паутинка»");

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sent.getReplyMarkup();
        assertThat(callbackDataOf(keyboard)).containsExactly(DiseaseMenuService.LIST_CALLBACK);
    }

    @Test
    @DisplayName("sendSearchResults экранирует markdown-спецсимволы запроса в тексте «ничего не найдено»")
    void should_escapeMarkdownSpecialChars_when_queryEmptyResultHasSpecialChars() throws TelegramApiException {
        when(diseaseService.search("желт_ый*лист", 10)).thenReturn(List.of());

        service.sendSearchResults(user, "желт_ый*лист", telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText()).contains("желт\\_ый\\*лист");
    }

    @Test
    @DisplayName("sendSearchResults с найденными болезнями — клавиатура результатов + кнопка «Все болезни»")
    void should_sendResultsKeyboardWithAllDiseasesButton_when_searchFindsMatches() throws TelegramApiException {
        DiseaseCard spider = new DiseaseCard(3L, "Паутинный клещ", "Tetranychidae", "s", "t", "p");
        when(diseaseService.search("паутинка", 10)).thenReturn(List.of(spider));

        service.sendSearchResults(user, "паутинка", telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        SendMessage sent = captor.getValue();

        assertThat(sent.getText()).contains("Нашёл: 1. Выбери проблему:");

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sent.getReplyMarkup();
        assertThat(callbackDataOf(keyboard)).containsExactly(
                DiseaseMenuService.VIEW_PREFIX + "3",
                DiseaseMenuService.LIST_CALLBACK
        );
    }

    @Test
    @DisplayName("sendCard рендерит полную карточку с латинским названием")
    void should_renderFullCard_when_diseaseHasLatinName() throws TelegramApiException {
        DiseaseCard card = new DiseaseCard(5L, "Тля", "Aphidoidea", "мелкие насекомые", "обработать мылом", "осмотр раз в неделю");
        when(diseaseService.getById(5L)).thenReturn(card);

        service.sendCard(user, 5L, telegramClient);

        verify(userService).resetToIdle(user);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        SendMessage sent = captor.getValue();

        assertThat(sent.getText())
                .contains("Тля")
                .contains("Aphidoidea")
                .contains("Симптомы").contains("мелкие насекомые")
                .contains("Что делать").contains("обработать мылом")
                .contains("Профилактика").contains("осмотр раз в неделю");

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sent.getReplyMarkup();
        assertThat(buttonTextsOf(keyboard)).containsExactly("⬅️ К списку болезней");
        assertThat(callbackDataOf(keyboard)).containsExactly(DiseaseMenuService.LIST_CALLBACK);
    }

    @Test
    @DisplayName("sendCard пропускает строку латинского названия, если оно null")
    void should_omitLatinNameLine_when_latinNameIsNull() throws TelegramApiException {
        DiseaseCard card = new DiseaseCard(6L, "Гниль корней", null, "симптомы", "лечение", "профилактика");
        when(diseaseService.getById(6L)).thenReturn(card);

        service.sendCard(user, 6L, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        String[] lines = captor.getValue().getText().split("\n", -1);
        assertThat(lines[1]).isEmpty();
        assertThat(captor.getValue().getText()).contains("Симптомы").contains("симптомы");
    }

    @Test
    @DisplayName("sendCard пропускает строку латинского названия, если оно пустое/blank")
    void should_omitLatinNameLine_when_latinNameIsBlank() throws TelegramApiException {
        DiseaseCard card = new DiseaseCard(7L, "Ожог листьев", "   ", "симптомы", "лечение", "профилактика");
        when(diseaseService.getById(7L)).thenReturn(card);

        service.sendCard(user, 7L, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText()).doesNotContain("_   _");
    }

    @Test
    @DisplayName("sendCard шлёт «не найдена», если DiseaseService бросает DiseaseNotFoundException")
    void should_sendNotFoundMessage_when_diseaseServiceThrowsNotFound() throws TelegramApiException {
        when(diseaseService.getById(999L)).thenThrow(new DiseaseNotFoundException(999L));

        service.sendCard(user, 999L, telegramClient);

        verify(userService).resetToIdle(user);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        SendMessage sent = captor.getValue();

        assertThat(sent.getText()).isEqualTo("❌ Болезнь не найдена.");
        assertThat(sent.getParseMode()).isNull();
        assertThat(sent.getReplyMarkup()).isNull();
    }

    @Test
    @DisplayName("sendList не падает, если Telegram API бросает исключение при отправке")
    void should_swallowTelegramException_when_sendListExecuteFails() throws TelegramApiException {
        when(diseaseService.getAll()).thenReturn(List.of());
        doThrow(new TelegramApiException("boom")).when(telegramClient).execute(any(SendMessage.class));

        service.sendList(user, telegramClient);

        verify(telegramClient).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("startSearch не падает, если Telegram API бросает исключение при отправке")
    void should_swallowTelegramException_when_startSearchExecuteFails() throws TelegramApiException {
        doThrow(new TelegramApiException("boom")).when(telegramClient).execute(any(SendMessage.class));

        service.startSearch(user, telegramClient);

        verify(telegramClient).execute(any(SendMessage.class));
        verify(userService).updateState(user, ConversationState.AWAITING_DISEASE_SEARCH);
    }

    @Test
    @DisplayName("sendCard не падает, если Telegram API бросает исключение при отправке карточки")
    void should_swallowTelegramException_when_sendCardExecuteFails() throws TelegramApiException {
        DiseaseCard card = new DiseaseCard(8L, "Тля", null, "s", "t", "p");
        when(diseaseService.getById(8L)).thenReturn(card);
        doThrow(new TelegramApiException("boom")).when(telegramClient).execute(any(SendMessage.class));

        service.sendCard(user, 8L, telegramClient);

        verify(telegramClient).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("sendCard для найденной болезни никогда не шлёт «не найдена»")
    void should_notCallGetByIdTwice_when_cardFound() {
        DiseaseCard card = new DiseaseCard(9L, "Тля", null, "s", "t", "p");
        when(diseaseService.getById(9L)).thenReturn(card);

        service.sendCard(user, 9L, telegramClient);

        verify(diseaseService).getById(9L);
        verify(userService, never()).updateState(any(), any());
    }
}

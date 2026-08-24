package com.plantcare.bot.service;

import com.plantcare.core.domain.ShoppingItem;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.ShoppingListService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для ShoppingListMenuService")
class ShoppingListMenuServiceTest {

    @Mock private ShoppingListService shoppingListService;
    @Mock private TelegramClient telegramClient;

    @InjectMocks
    private ShoppingListMenuService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().telegramChatId(777L).build();
    }

    private static ShoppingItem item(long id, String title, boolean checked) {
        ShoppingItem item = new ShoppingItem(null, title);
        ReflectionTestUtils.setField(item, "id", id);
        item.setChecked(checked);
        return item;
    }

    @Test
    @DisplayName("Пустой список — текст с призывом добавить, кнопки только «Добавить» и «Назад»")
    void shouldShowEmptyListText() throws TelegramApiException {
        when(shoppingListService.list(user.getId())).thenReturn(List.of());

        service.sendShoppingList(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        SendMessage sent = captor.getValue();
        assertThat(sent.getChatId()).isEqualTo("777");
        assertThat(sent.getText()).contains("Список пуст. Добавь, что нужно купить 🌱");

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sent.getReplyMarkup();
        List<String> callbackData = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
        assertThat(callbackData).containsExactly("SHOPPING:ADD", "MENU:BACK");
    }

    @Test
    @DisplayName("Список с непроверенной позицией — чекбокс ☐, action CHECK, без кнопки очистки")
    void shouldRenderUncheckedItemWithCheckAction() throws TelegramApiException {
        ShoppingItem unchecked = item(5L, "Земля", false);
        when(shoppingListService.list(user.getId())).thenReturn(List.of(unchecked));

        service.sendShoppingList(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        try {
            verify(telegramClient).execute(captor.capture());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
        SendMessage sent = captor.getValue();
        assertThat(sent.getText()).contains("Нажми на позицию, чтобы отметить её купленной.");

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sent.getReplyMarkup();
        List<InlineKeyboardButton> buttons = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .toList();

        assertThat(buttons.get(0).getText()).isEqualTo("☐ Земля");
        assertThat(buttons.get(0).getCallbackData()).isEqualTo("SHOPPING:CHECK:5");

        List<String> callbackData = buttons.stream().map(InlineKeyboardButton::getCallbackData).toList();
        assertThat(callbackData).doesNotContain("SHOPPING:CLEAR");
        assertThat(callbackData).containsExactly("SHOPPING:CHECK:5", "SHOPPING:ADD", "MENU:BACK");
    }

    @Test
    @DisplayName("Список с отмеченной позицией — чекбокс ☑, action UNCHECK, кнопка очистки появляется")
    void shouldRenderCheckedItemWithUncheckActionAndClearButton() throws TelegramApiException {
        ShoppingItem checked = item(6L, "Горшок", true);
        when(shoppingListService.list(user.getId())).thenReturn(List.of(checked));

        service.sendShoppingList(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        try {
            verify(telegramClient).execute(captor.capture());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
        List<InlineKeyboardButton> buttons = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .toList();

        assertThat(buttons.get(0).getText()).isEqualTo("☑ Горшок");
        assertThat(buttons.get(0).getCallbackData()).isEqualTo("SHOPPING:UNCHECK:6");

        List<String> callbackData = buttons.stream().map(InlineKeyboardButton::getCallbackData).toList();
        assertThat(callbackData).containsExactly(
                "SHOPPING:UNCHECK:6", "SHOPPING:ADD", "SHOPPING:CLEAR", "MENU:BACK");
    }

    @Test
    @DisplayName("Смешанный список — очистка появляется, если хотя бы одна позиция отмечена")
    void shouldShowClearButtonWhenAtLeastOneItemChecked() throws TelegramApiException {
        ShoppingItem unchecked = item(5L, "Земля", false);
        ShoppingItem checked = item(6L, "Горшок", true);
        when(shoppingListService.list(user.getId())).thenReturn(List.of(unchecked, checked));

        service.sendShoppingList(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        try {
            verify(telegramClient).execute(captor.capture());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
        List<String> callbackData = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
        assertThat(callbackData).contains("SHOPPING:CLEAR");
    }

    @Test
    @DisplayName("Ошибка отправки — исключение не выбрасывается наружу")
    void shouldSwallowTelegramApiExceptionOnSend() throws TelegramApiException {
        when(shoppingListService.list(user.getId())).thenReturn(List.of());
        doThrow(new TelegramApiException("boom")).when(telegramClient).execute(any(SendMessage.class));

        service.sendShoppingList(user, telegramClient);

        verify(telegramClient).execute(any(SendMessage.class));
    }
}

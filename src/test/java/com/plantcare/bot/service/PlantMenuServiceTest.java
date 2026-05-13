package com.plantcare.bot.service;

import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.repository.PlantRepository;
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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для PlantMenuService")
class PlantMenuServiceTest {

    @Mock private PlantRepository plantRepository;
    @Mock private TelegramClient client;

    @InjectMocks
    private PlantMenuService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(100L)
                .build();
        setPrivateField(user, "id", 7L);
    }

    @Test
    @DisplayName("Пустой список — текст «пока пусто» и кнопка «в меню»")
    void shouldRenderEmptyList() throws TelegramApiException {
        when(plantRepository.findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(7L))
                .thenReturn(List.of());

        service.sendMyPlantsList(user, null, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        SendMessage sent = captor.getValue();
        assertThat(sent.getText()).contains("Мои растения").contains("Пока пусто");

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sent.getReplyMarkup();
        // Только кнопка «В главное меню»
        assertThat(keyboard.getKeyboard()).hasSize(1);
        assertThat(keyboard.getKeyboard().get(0).get(0).getCallbackData()).isEqualTo("MENU:BACK");
    }

    @Test
    @DisplayName("Список с растениями — кнопка на каждое + кнопка «в меню»")
    void shouldRenderPlantsWithButtons() throws TelegramApiException {
        Plant a = plant(1L, "Алоэ", location(10L, "Кухня", "🍳"));
        Plant b = plant(2L, "Фикус", location(11L, "Спальня", null));

        when(plantRepository.findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(7L))
                .thenReturn(List.of(a, b));

        service.sendMyPlantsList(user, null, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        SendMessage sent = captor.getValue();
        assertThat(sent.getText()).contains("Всего: 2");

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sent.getReplyMarkup();
        // 2 растения + back
        assertThat(keyboard.getKeyboard()).hasSize(3);

        InlineKeyboardButton firstPlantBtn = keyboard.getKeyboard().get(0).get(0);
        assertThat(firstPlantBtn.getText()).contains("Алоэ").contains("🍳");
        assertThat(firstPlantBtn.getCallbackData()).isEqualTo("PLANT:VIEW:1");

        InlineKeyboardButton secondPlantBtn = keyboard.getKeyboard().get(1).get(0);
        // У комнаты нет эмодзи — используем «· name» фолбэк
        assertThat(secondPlantBtn.getText()).contains("Фикус").contains("Спальня");
        assertThat(secondPlantBtn.getCallbackData()).isEqualTo("PLANT:VIEW:2");

        // Последняя строка — back
        InlineKeyboardRow lastRow = keyboard.getKeyboard().get(2);
        assertThat(lastRow.get(0).getCallbackData()).isEqualTo("MENU:BACK");
    }

    @Test
    @DisplayName("С messageId — рендерится через EditMessageText, не через SendMessage")
    void shouldEditInPlaceWhenMessageIdGiven() throws TelegramApiException {
        when(plantRepository.findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(7L))
                .thenReturn(List.of());

        service.sendMyPlantsList(user, 999, client);

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(client).execute(captor.capture());

        EditMessageText edit = captor.getValue();
        assertThat(edit.getMessageId()).isEqualTo(999);
        assertThat(edit.getChatId()).isEqualTo("100");
    }

    // ---- helpers ----

    private Plant plant(Long id, String name, Location loc) {
        Plant p = Plant.builder().name(name).location(loc).build();
        setPrivateField(p, "id", id);
        return p;
    }

    private Location location(Long id, String name, String emoji) {
        Location loc = Location.builder().name(name).emoji(emoji).build();
        setPrivateField(loc, "id", id);
        return loc;
    }

    private void setPrivateField(Object target, String fieldName, Object value) {
        try {
            Class<?> clazz = target.getClass();
            Field field = null;
            while (clazz != null) {
                try {
                    field = clazz.getDeclaredField(fieldName);
                    break;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            if (field == null) {
                throw new RuntimeException("Field not found: " + fieldName);
            }
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

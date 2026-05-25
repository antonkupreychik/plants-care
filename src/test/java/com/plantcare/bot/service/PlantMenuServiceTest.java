package com.plantcare.bot.service;

import com.plantcare.core.service.HealthScoreService;

import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.HealthZone;
import com.plantcare.core.repository.PlantRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для PlantMenuService")
class PlantMenuServiceTest {

    @Mock private PlantRepository plantRepository;
    @Mock private HealthScoreService healthScoreService;
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

        // issue #138: по умолчанию «мало данных» — сводка по зонам не печатается,
        // существующие ассерты про текст/клавиатуру не задеваются.
        lenient().when(healthScoreService.computeForPlant(any()))
                .thenReturn(HealthScoreService.HealthScore.insufficient());
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

    @Test
    @DisplayName("issue #117: кнопка «📦 Архив (N)» появляется только при N > 0 и содержит счётчик")
    void should_render_archive_button_with_counter_when_archived_count_positive()
            throws TelegramApiException {
        // arrange: одно активное растение + 3 архивных
        Plant active = plant(1L, "Алоэ", location(10L, "Кухня", "🍳"));
        when(plantRepository.findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(7L))
                .thenReturn(List.of(active));
        when(plantRepository.countByUserIdAndArchivedAtIsNotNull(7L)).thenReturn(3L);

        // act
        service.sendMyPlantsList(user, null, client);

        // assert
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
        // 1 растение + кнопка архива + back
        assertThat(keyboard.getKeyboard()).hasSize(3);

        InlineKeyboardButton archiveBtn = keyboard.getKeyboard().get(1).get(0);
        assertThat(archiveBtn.getText())
                .contains("Архив")
                .contains("3");
        assertThat(archiveBtn.getCallbackData()).isEqualTo("ARCHIVE:LIST");
    }

    @Test
    @DisplayName("issue #117: при 0 архивных кнопка «📦 Архив» НЕ отрисовывается")
    void should_not_render_archive_button_when_archived_count_zero()
            throws TelegramApiException {
        // arrange
        Plant active = plant(1L, "Алоэ", location(10L, "Кухня", "🍳"));
        when(plantRepository.findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(7L))
                .thenReturn(List.of(active));
        when(plantRepository.countByUserIdAndArchivedAtIsNotNull(7L)).thenReturn(0L);

        // act
        service.sendMyPlantsList(user, null, client);

        // assert
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
        // только 1 растение + back, никакой архив-кнопки
        assertThat(keyboard.getKeyboard()).hasSize(2);
        assertThat(keyboard.getKeyboard())
                .flatExtracting(row -> row)
                .extracting(InlineKeyboardButton::getCallbackData)
                .doesNotContain("ARCHIVE:LIST");
    }

    @Test
    @DisplayName("issue #138: сводка «🟢 N · 🟡 N · 🔴 N» считает зоны, insufficient не учитывается")
    void should_render_health_summary_with_zone_counts_excluding_insufficient_plants()
            throws TelegramApiException {
        // arrange: 5 растений — 2 GREEN, 1 YELLOW, 1 RED, 1 insufficient
        Plant green1 = plant(1L, "Алоэ", location(10L, "Кухня", "🍳"));
        Plant green2 = plant(2L, "Бегония", location(10L, "Кухня", "🍳"));
        Plant yellow = plant(3L, "Фикус", location(11L, "Спальня", null));
        Plant red = plant(4L, "Орхидея", location(11L, "Спальня", null));
        Plant noData = plant(5L, "Кактус", location(11L, "Спальня", null));

        when(plantRepository.findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(7L))
                .thenReturn(List.of(green1, green2, yellow, red, noData));
        when(healthScoreService.computeForPlant(green1))
                .thenReturn(HealthScoreService.HealthScore.of(90, HealthZone.GREEN));
        when(healthScoreService.computeForPlant(green2))
                .thenReturn(HealthScoreService.HealthScore.of(81, HealthZone.GREEN));
        when(healthScoreService.computeForPlant(yellow))
                .thenReturn(HealthScoreService.HealthScore.of(55, HealthZone.YELLOW));
        when(healthScoreService.computeForPlant(red))
                .thenReturn(HealthScoreService.HealthScore.of(20, HealthZone.RED));
        when(healthScoreService.computeForPlant(noData))
                .thenReturn(HealthScoreService.HealthScore.insufficient());

        // act
        service.sendMyPlantsList(user, null, client);

        // assert: insufficient (Кактус) в счётчики не попал → 2 / 1 / 1
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("🟢 2 · 🟡 1 · 🔴 1");
    }

    @Test
    @DisplayName("issue #138: при всех insufficient строка-сводка не печатается")
    void should_not_render_health_summary_when_all_plants_have_insufficient_data()
            throws TelegramApiException {
        // arrange: оба растения без достаточных данных (дефолтный стаб из setUp)
        Plant a = plant(1L, "Алоэ", location(10L, "Кухня", "🍳"));
        Plant b = plant(2L, "Фикус", location(11L, "Спальня", null));
        when(plantRepository.findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(7L))
                .thenReturn(List.of(a, b));

        // act
        service.sendMyPlantsList(user, null, client);

        // assert: ни одной зональной строки
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        String text = captor.getValue().getText();
        assertThat(text).contains("Всего: 2");
        assertThat(text).doesNotContain("🟢");
        assertThat(text).doesNotContain("🟡");
        assertThat(text).doesNotContain("🔴");
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

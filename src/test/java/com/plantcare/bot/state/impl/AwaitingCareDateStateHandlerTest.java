package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.service.BackdatedCareCallbackService;
import com.plantcare.bot.service.PlantService;
import com.plantcare.bot.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты обработчика состояния {@link AwaitingCareDateStateHandler} (issue #118).
 *
 * <p>Hander сам не парсит даты — делегирует в {@link BackdatedCareCallbackService#parseCareDate}.
 * Поэтому здесь моки определяют, что вернёт парсер, и мы проверяем поведение
 * handler'а (валидация контекста, reset-to-idle на успехе, удержание state на ошибке).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AwaitingCareDateStateHandler (#118)")
class AwaitingCareDateStateHandlerTest {

    @Mock private UserService userService;
    @Mock private PlantService plantService;
    @Mock private BackdatedCareCallbackService backdatedCareCallbackService;
    @Mock private TelegramClient client;

    @InjectMocks
    private AwaitingCareDateStateHandler handler;

    private User user;
    private Plant plant;

    @BeforeEach
    void setUp() {
        Map<String, Object> stateData = new HashMap<>();
        stateData.put(BackdatedCareCallbackService.STATE_PLANT_ID, "7");
        stateData.put(BackdatedCareCallbackService.STATE_TASK_TYPE, "WATERING");

        user = User.builder()
                .telegramChatId(100L)
                .timezone("UTC")
                .conversationState(ConversationState.AWAITING_CARE_DATE)
                .stateData(stateData)
                .build();
        setId(user, 42L);

        plant = Plant.builder()
                .user(user)
                .name("Монстера")
                .build();
        setId(plant, 7L);

        when(plantService.getPlantForUser(42L, 7L)).thenReturn(Optional.of(plant));
    }

    @Test
    @DisplayName("Возвращает корректный supported state")
    void should_report_supported_state() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_CARE_DATE);
    }

    // ====================================================================
    // Happy path
    // ====================================================================

    @Test
    @DisplayName("Валидная дата → applyResult вызывается + state сбрасывается в IDLE")
    void should_apply_result_and_reset_state_on_valid_date() {
        LocalDate parsed = LocalDate.of(2026, 5, 22);
        when(backdatedCareCallbackService.parseCareDate(eq("22.05"), eq(user)))
                .thenReturn(Optional.of(parsed));

        handler.handle(user, textUpdate("22.05"), client);

        verify(backdatedCareCallbackService).applyResult(
                eq(user), eq(plant), eq(TaskType.WATERING), eq(parsed),
                eq(user.getTelegramChatId()), eq((Integer) null), eq((String) null), eq(client));
        verify(userService).resetToIdle(user);
    }

    @Test
    @DisplayName("Полный формат DD.MM.YYYY → applyResult с распарсенной датой")
    void should_apply_for_full_date_format() {
        LocalDate parsed = LocalDate.of(2026, 5, 22);
        when(backdatedCareCallbackService.parseCareDate(eq("22.05.2026"), eq(user)))
                .thenReturn(Optional.of(parsed));

        handler.handle(user, textUpdate("22.05.2026"), client);

        verify(backdatedCareCallbackService).applyResult(
                eq(user), eq(plant), eq(TaskType.WATERING), eq(parsed),
                any(), any(), any(), eq(client));
        verify(userService).resetToIdle(user);
    }

    // ====================================================================
    // Edge: невалидный ввод не должен снимать state
    // ====================================================================

    @Test
    @DisplayName("Невалидный формат → подсказка, state остаётся AWAITING_CARE_DATE, applyResult НЕ вызывается")
    void should_keep_state_when_input_unparseable() throws TelegramApiException {
        when(backdatedCareCallbackService.parseCareDate(eq("abc"), eq(user)))
                .thenReturn(Optional.empty());

        handler.handle(user, textUpdate("abc"), client);

        verify(backdatedCareCallbackService, never()).applyResult(any(), any(), any(), any(),
                any(), any(), any(), any());
        verify(userService, never()).resetToIdle(any());

        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(cap.capture());
        assertThat(cap.getValue().getText())
                .contains("Не понял")
                .contains("DD.MM");
    }

    @Test
    @DisplayName("Не-текстовое сообщение → подсказка, ничего не делает")
    void should_send_hint_when_message_has_no_text() throws TelegramApiException {
        Update update = new Update();
        update.setMessage(new Message());

        handler.handle(user, update, client);

        verify(backdatedCareCallbackService, never()).applyResult(any(), any(), any(), any(),
                any(), any(), any(), any());
        verify(userService, never()).resetToIdle(any());

        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(cap.capture());
        assertThat(cap.getValue().getText()).contains("DD.MM");
    }

    // ====================================================================
    // Edge: контекст
    // ====================================================================

    @Test
    @DisplayName("Отсутствует plantId в stateData → reset to idle + сообщение 'Контекст утерян'")
    void should_reset_to_idle_when_plant_id_missing() throws TelegramApiException {
        user.getStateData().remove(BackdatedCareCallbackService.STATE_PLANT_ID);

        handler.handle(user, textUpdate("22.05"), client);

        verify(userService).resetToIdle(user);
        verify(backdatedCareCallbackService, never()).applyResult(any(), any(), any(), any(),
                any(), any(), any(), any());

        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(cap.capture());
        assertThat(cap.getValue().getText()).contains("Контекст утерян");
    }

    @Test
    @DisplayName("Невалидный taskType в stateData → reset to idle + сообщение об ошибке")
    void should_reset_to_idle_when_task_type_invalid() throws TelegramApiException {
        user.getStateData().put(BackdatedCareCallbackService.STATE_TASK_TYPE, "BOGUS");

        handler.handle(user, textUpdate("22.05"), client);

        verify(userService).resetToIdle(user);
        verify(backdatedCareCallbackService, never()).applyResult(any(), any(), any(), any(),
                any(), any(), any(), any());

        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(cap.capture());
        assertThat(cap.getValue().getText()).contains("Внутренняя ошибка");
    }

    @Test
    @DisplayName("Растение не найдено (могли архивировать в параллельной сессии) → reset to idle + сообщение")
    void should_reset_to_idle_when_plant_not_found() throws TelegramApiException {
        when(plantService.getPlantForUser(42L, 7L)).thenReturn(Optional.empty());

        handler.handle(user, textUpdate("22.05"), client);

        verify(userService).resetToIdle(user);
        verify(backdatedCareCallbackService, never()).applyResult(any(), any(), any(), any(),
                any(), any(), any(), any());

        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(cap.capture());
        assertThat(cap.getValue().getText()).contains("Растение не найдено");
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private Update textUpdate(String text) {
        Update update = new Update();
        Message msg = new Message();
        msg.setText(text);
        update.setMessage(msg);
        return update;
    }

    private static void setId(Object target, Long id) {
        try {
            Class<?> clazz = target.getClass();
            Field field = null;
            while (clazz != null) {
                try {
                    field = clazz.getDeclaredField("id");
                    break;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            if (field == null) throw new RuntimeException("id field not found");
            field.setAccessible(true);
            field.set(target, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

package com.plantcare.bot.state.impl;

import com.plantcare.bot.service.PlantCardService;
import com.plantcare.core.domain.enums.TaskType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты для {@link EditStateData} — чистого парсера edit_*-ключей stateData.
 */
@DisplayName("Unit-тесты для EditStateData")
class EditStateDataTest {

    // ---------------------------------------------------------------
    // plantId
    // ---------------------------------------------------------------

    @Test
    @DisplayName("plantId парсит числовой edit_plant_id")
    void shouldParsePlantId() {
        Map<String, Object> stateData = Map.of("edit_plant_id", "42");

        assertThat(EditStateData.plantId(stateData)).isEqualTo(42L);
    }

    @Test
    @DisplayName("plantId возвращает null, если stateData сам по себе null")
    void shouldReturnNullPlantIdWhenStateDataIsNull() {
        assertThat(EditStateData.plantId(null)).isNull();
    }

    @Test
    @DisplayName("plantId возвращает null, если ключ отсутствует")
    void shouldReturnNullPlantIdWhenKeyMissing() {
        assertThat(EditStateData.plantId(Map.of())).isNull();
    }

    @Test
    @DisplayName("plantId возвращает null при нечисловом значении")
    void shouldReturnNullPlantIdWhenValueNotNumeric() {
        Map<String, Object> stateData = Map.of("edit_plant_id", "not-a-number");

        assertThat(EditStateData.plantId(stateData)).isNull();
    }

    // ---------------------------------------------------------------
    // messageId
    // ---------------------------------------------------------------

    @Test
    @DisplayName("messageId парсит числовой edit_message_id")
    void shouldParseMessageId() {
        Map<String, Object> stateData = Map.of("edit_message_id", "777");

        assertThat(EditStateData.messageId(stateData)).isEqualTo(777);
    }

    @Test
    @DisplayName("messageId возвращает null, если stateData сам по себе null")
    void shouldReturnNullMessageIdWhenStateDataIsNull() {
        assertThat(EditStateData.messageId(null)).isNull();
    }

    @Test
    @DisplayName("messageId возвращает null, если ключ отсутствует")
    void shouldReturnNullMessageIdWhenKeyMissing() {
        assertThat(EditStateData.messageId(Map.of())).isNull();
    }

    @Test
    @DisplayName("messageId возвращает null при нечисловом значении")
    void shouldReturnNullMessageIdWhenValueNotNumeric() {
        Map<String, Object> stateData = Map.of("edit_message_id", "abc");

        assertThat(EditStateData.messageId(stateData)).isNull();
    }

    // ---------------------------------------------------------------
    // backTarget
    // ---------------------------------------------------------------

    @Test
    @DisplayName("backTarget возвращает заданное значение")
    void shouldReturnConfiguredBackTarget() {
        Map<String, Object> stateData = Map.of("edit_back_target", "CARD");

        assertThat(EditStateData.backTarget(stateData)).isEqualTo("CARD");
    }

    @Test
    @DisplayName("backTarget возвращает BACK_TO_LIST, если stateData сам по себе null")
    void shouldFallBackToListWhenStateDataIsNull() {
        assertThat(EditStateData.backTarget(null)).isEqualTo(PlantCardService.BACK_TO_LIST);
    }

    @Test
    @DisplayName("backTarget возвращает BACK_TO_LIST, если ключ отсутствует")
    void shouldFallBackToListWhenKeyMissing() {
        assertThat(EditStateData.backTarget(Map.of())).isEqualTo(PlantCardService.BACK_TO_LIST);
    }

    @Test
    @DisplayName("backTarget возвращает BACK_TO_LIST, если значение пустая строка")
    void shouldFallBackToListWhenValueBlank() {
        Map<String, Object> stateData = new HashMap<>();
        stateData.put("edit_back_target", "   ");

        assertThat(EditStateData.backTarget(stateData)).isEqualTo(PlantCardService.BACK_TO_LIST);
    }

    // ---------------------------------------------------------------
    // taskType
    // ---------------------------------------------------------------

    @Test
    @DisplayName("taskType парсит валидное имя enum-константы")
    void shouldParseValidTaskType() {
        Map<String, Object> stateData = Map.of("edit_task_type", "WATERING");

        assertThat(EditStateData.taskType(stateData)).isEqualTo(TaskType.WATERING);
    }

    @Test
    @DisplayName("taskType возвращает null, если stateData сам по себе null")
    void shouldReturnNullTaskTypeWhenStateDataIsNull() {
        assertThat(EditStateData.taskType(null)).isNull();
    }

    @Test
    @DisplayName("taskType возвращает null, если ключ отсутствует")
    void shouldReturnNullTaskTypeWhenKeyMissing() {
        assertThat(EditStateData.taskType(Map.of())).isNull();
    }

    @Test
    @DisplayName("taskType возвращает null при незнакомом имени константы")
    void shouldReturnNullTaskTypeWhenValueUnknown() {
        Map<String, Object> stateData = Map.of("edit_task_type", "NOT_A_TASK_TYPE");

        assertThat(EditStateData.taskType(stateData)).isNull();
    }
}

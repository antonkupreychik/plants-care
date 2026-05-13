package com.plantcare.bot.state.impl;

import com.plantcare.bot.service.PlantCardService;
import com.plantcare.bot.domain.enums.TaskType;

import java.util.Map;

/**
 * Утилита для state-handler'ов режима редактирования: разбирает edit_*-ключи из stateData.
 *
 * Контекст редактирования помещают в stateData при открытии экрана-промпта
 * (см. MenuCallbackService.startEditFlow). Здесь — только чтение.
 */
final class EditStateData {

    static final String PLANT_ID = "edit_plant_id";
    static final String MESSAGE_ID = "edit_message_id";
    static final String BACK_TARGET = "edit_back_target";
    static final String TASK_TYPE = "edit_task_type";

    private EditStateData() {}

    static Long plantId(Map<String, Object> stateData) {
        return parseLong(stateData == null ? null : stateData.get(PLANT_ID));
    }

    static Integer messageId(Map<String, Object> stateData) {
        Object raw = stateData == null ? null : stateData.get(MESSAGE_ID);
        if (raw == null) return null;
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String backTarget(Map<String, Object> stateData) {
        Object raw = stateData == null ? null : stateData.get(BACK_TARGET);
        if (raw == null) return PlantCardService.BACK_TO_LIST;
        String s = String.valueOf(raw);
        return s.isBlank() ? PlantCardService.BACK_TO_LIST : s;
    }

    static TaskType taskType(Map<String, Object> stateData) {
        Object raw = stateData == null ? null : stateData.get(TASK_TYPE);
        if (raw == null) return null;
        try {
            return TaskType.valueOf(String.valueOf(raw));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Long parseLong(Object raw) {
        if (raw == null) return null;
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

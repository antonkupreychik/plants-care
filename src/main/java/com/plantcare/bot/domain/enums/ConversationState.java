package com.plantcare.bot.domain.enums;

public enum ConversationState {
    IDLE,

    // Онбординг
    AWAITING_TIMEZONE,
    AWAITING_TIMEZONE_MANUAL,

    // Создание растения
    AWAITING_PLANT_SPECIES_CHOICE,
    AWAITING_PLANT_SPECIES_SEARCH,
    AWAITING_PLANT_NAME,
    AWAITING_PLANT_ROOM,
    AWAITING_PLANT_WATERING_INTERVAL,
    AWAITING_PLANT_LAST_WATERED,

    // Работа с комнатами
    AWAITING_ROOM_NAME,

    // Заметки
    AWAITING_PLANT_NOTE,

    // Изменение расписания
    AWAITING_NEW_INTERVAL
}

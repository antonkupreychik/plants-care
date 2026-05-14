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
    AWAITING_PLANT_MISTING_SETUP,      // Настройка опрыскивания (после создания)
    AWAITING_PLANT_FERTILIZING_SETUP,  // Настройка удобрения (после опрыскивания)
    AWAITING_PLANT_PHOTO,              // Загрузка фото растения (после удобрения)

    // Работа с комнатами
    AWAITING_LOCATION_RENAME,
    AWAITING_LOCATION_CHANGE_EMOJI,
    AWAITING_ROOM_NAME,
    AWAITING_LOCATION_NAME,
    AWAITING_LOCATION_EMOJI,
    AWAITING_PLANT_LOCATION_NAME,
    AWAITING_PLANT_LOCATION_EMOJI,
    // Заметки
    AWAITING_PLANT_NOTE,

    // Редактирование информации о растении (issue #27)
    AWAITING_PLANT_RENAME,
    AWAITING_PLANT_PHOTO_EDIT,

    // Изменение расписания
    AWAITING_NEW_INTERVAL,

    // Пользовательские шаблоны (issue #68)
    AWAITING_TEMPLATE_NAME,            // ввод имени при сохранении шаблона
    AWAITING_PLANT_NAME_FROM_TEMPLATE, // ввод имени растения при создании из шаблона
    AWAITING_TEMPLATE_RENAME           // ввод нового имени при переименовании шаблона
}
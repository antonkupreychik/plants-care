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
    AWAITING_PLANT_ACQUIRED_CHOICE,    // issue #117: выбор «когда завёл растение?»
    AWAITING_PLANT_ACQUIRED_DATE,      // issue #117: ввод даты «давно (укажу)»
    AWAITING_PLANT_ROOM,
    AWAITING_PLANT_WATERING_INTERVAL,
    AWAITING_PLANT_LAST_WATERED,
    AWAITING_PLANT_MISTING_SETUP,      // Настройка опрыскивания (после создания)
    AWAITING_PLANT_FERTILIZING_SETUP,  // Настройка удобрения (после опрыскивания)
    AWAITING_PLANT_PHOTO,              // Загрузка фото растения (после удобрения)
    AWAITING_PLANT_ACCLIMATION_CHOICE, // issue #75: «Это новое растение?» — блокирующий шаг

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
    AWAITING_TEMPLATE_RENAME,          // ввод нового имени при переименовании шаблона

    // Родословная (issue #139, ADR-012): ввод имени черенка при создании потомка.
    // Контекст parent_plant_id — в stateData.
    AWAITING_CUTTING_NAME,

    // Интеграция с погодой (issue #69)
    AWAITING_WEATHER_LOCATION,
    AWAITING_PLANT_DIAGNOSIS, //(issue #73)
    // ожидаем location-сообщение для weather_lat/lon
    // Отпуск (issue #53)
    AWAITING_VACATION_DAYS,          // ввод числа дней отпуска при выборе «Другое»

    // Фото-прогресс (issue #72)
    AWAITING_PROGRESS_PHOTO,           // ждём фото для таймлайна (план «📸 Фото-прогресс»)

    // Ретро-отметка ухода (issue #118): юзер ввёл "Выбрать дату" → ждём DD.MM[.YYYY].
    AWAITING_CARE_DATE,

    // Список покупок (issue #136): ждём текст новой позиции чек-листа.
    AWAITING_SHOPPING_ITEM,

    // Поиск по справочнику болезней (issue #140): ждём текст симптома/названия.
    AWAITING_DISEASE_SEARCH
}
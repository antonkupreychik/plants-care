package com.plantcare.admin.audit;

import lombok.Getter;

/**
 * Словарь админских действий, попадающих в аудит-лог (issue #98).
 *
 * <p>Константа = то, что реально умеет админка сегодня. Названий из issue,
 * которым нет соответствующей операции в коде ({@code USER_DELETE},
 * {@code USERS_MERGE}, {@code LINK_DISCONNECT}), здесь намеренно нет:
 * заводить их «на будущее» значило бы держать в словаре мёртвые значения.
 * Новое деструктивное действие добавляет свою константу вместе с собой.
 *
 * <p>Хранится в БД как {@code name()} (VARCHAR), поэтому переименование
 * константы ломает исторические записи — только добавление.
 */
@Getter
public enum AdminAuditAction {

    USER_RESET_STATE("Сброс состояния юзера"),
    USER_TOGGLE_BLOCK("Блокировка/разблокировка юзера"),
    USER_PAUSE("Пауза напоминаний"),
    USER_UNPAUSE("Снятие паузы"),
    USER_SEND_MESSAGE("Отправка сообщения юзеру"),
    USER_FLAG_SET("Включение feature-флага"),
    USER_FLAG_CLEAR("Снятие feature-флага"),
    STUCK_BULK_RESET("Массовый сброс застрявших"),
    BROADCAST_SENT("Запуск рассылки"),
    BROADCAST_STOPPED("Останов рассылки"),
    SPECIES_CREATE("Создание вида"),
    SPECIES_UPDATE("Изменение вида"),
    SPECIES_DELETE("Удаление вида"),
    SPECIES_FACT_CREATE("Создание факта о виде"),
    SPECIES_FACT_UPDATE("Изменение факта о виде"),
    SPECIES_FACT_DELETE("Удаление факта о виде");

    private final String label;

    AdminAuditAction(String label) {
        this.label = label;
    }

    /**
     * Мягкий разбор значения из query-параметра фильтра: мусор превращается
     * в {@code null} («без фильтра»), а не в 400 на странице аудита.
     */
    public static AdminAuditAction parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

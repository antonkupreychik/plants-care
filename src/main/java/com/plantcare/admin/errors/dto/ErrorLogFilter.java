package com.plantcare.admin.errors.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Набор фильтров списка ошибок на {@code /admin/errors} (issue #97).
 *
 * <p>Все поля опциональны и комбинируются через AND. {@code null}/пустое = «не фильтруем».
 *
 * @param from      нижняя граница по дате (включительно, UTC-полночь)
 * @param to        верхняя граница по дате (включительно — конвертируется в полночь СЛЕДУЮЩЕГО дня)
 * @param userId    {@code users.id}
 * @param logger    префикс имени логгера ({@code com.plantcare.bot})
 * @param message   подстрока в тексте сообщения (регистронезависимо)
 * @param level     {@code ERROR} / {@code WARN}
 * @param fingerprint точное совпадение группы (переход из топ-10)
 */
public record ErrorLogFilter(
        LocalDate from,
        LocalDate to,
        Long userId,
        String logger,
        String message,
        String level,
        String fingerprint
) {

    /** Пустой фильтр — «показать всё». */
    public static ErrorLogFilter empty() {
        return new ErrorLogFilter(null, null, null, null, null, null, null);
    }

    public Instant fromInstant() {
        return from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Верхняя граница делается эксклюзивной полуночью следующего дня — иначе «по 5 мая» терял 5 мая. */
    public Instant toInstantExclusive() {
        return to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public boolean isEmpty() {
        return from == null && to == null && userId == null
                && isBlank(logger) && isBlank(message) && isBlank(level) && isBlank(fingerprint);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

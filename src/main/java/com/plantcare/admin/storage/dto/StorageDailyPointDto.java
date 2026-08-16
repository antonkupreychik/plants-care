package com.plantcare.admin.storage.dto;

import java.time.LocalDate;

/**
 * Точка графика роста объёма на /admin/storage (issue #101) — строка
 * {@code storage_metrics} за одни сутки.
 *
 * @param date       дата снапшота
 * @param totalBytes объём в бакете на конец суток
 * @param totalCount количество объектов в бакете на конец суток
 */
public record StorageDailyPointDto(LocalDate date, long totalBytes, long totalCount) {

    public String totalHuman() {
        return ByteFormat.humanize(totalBytes);
    }

    /** Мегабайты — единица оси Y на спарклайне (в байтах шкала нечитаема). */
    public double megabytes() {
        return totalBytes <= 0 ? 0.0 : (double) totalBytes / (1024.0 * 1024.0);
    }
}

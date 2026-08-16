package com.plantcare.admin.storage.dto;

import java.util.Locale;

/**
 * Карточки-агрегаты на /admin/storage (issue #101).
 *
 * <p>Три разных «объёма», которые легко перепутать, поэтому явно:
 * <ul>
 *   <li>{@code bucket*} — что ФИЗИЧЕСКИ лежит в бакете ({@code purged_at IS NULL}).
 *       Именно за это платим.</li>
 *   <li>{@code active*} — что видно пользователю ({@code deleted_at IS NULL}).</li>
 *   <li>{@code pendingPurge*} — soft-deleted, но ещё не вычищенное: оно всё ещё
 *       в бакете и всё ещё стоит денег, пока не истечёт retention.</li>
 * </ul>
 *
 * @param bucketBytes       суммарный размер объектов в бакете
 * @param bucketCount       количество объектов в бакете
 * @param activeBytes       размер не-удалённых фото
 * @param activeCount       количество не-удалённых фото
 * @param pendingPurgeBytes размер soft-deleted, ещё лежащего в бакете
 * @param pendingPurgeCount количество soft-deleted, ещё лежащего в бакете
 * @param purgedCount       количество уже физически вычищенных записей (тумбстоуны)
 * @param pricePerGbMonth   тариф хранения, USD за ГБ в месяц (из конфига)
 * @param retentionDays     через сколько дней после soft-delete идёт физическая чистка
 */
public record StorageOverviewDto(
        long bucketBytes,
        long bucketCount,
        long activeBytes,
        long activeCount,
        long pendingPurgeBytes,
        long pendingPurgeCount,
        long purgedCount,
        double pricePerGbMonth,
        int retentionDays
) {

    public static StorageOverviewDto empty(double pricePerGbMonth, int retentionDays) {
        return new StorageOverviewDto(0, 0, 0, 0, 0, 0, 0, pricePerGbMonth, retentionDays);
    }

    public String bucketHuman() {
        return ByteFormat.humanize(bucketBytes);
    }

    public String activeHuman() {
        return ByteFormat.humanize(activeBytes);
    }

    public String pendingPurgeHuman() {
        return ByteFormat.humanize(pendingPurgeBytes);
    }

    /** Средний размер объекта в бакете. Нулевой счётчик — не делим. */
    public long avgBytes() {
        return bucketCount == 0 ? 0 : bucketBytes / bucketCount;
    }

    public String avgHuman() {
        return ByteFormat.humanize(avgBytes());
    }

    /**
     * Прикидка расходов за месяц: платим за то, что лежит в бакете, включая
     * ещё не вычищенное soft-deleted. Egress не считаем — на S3-совместимых
     * тарифах он либо нулевой, либо мы его не измеряем.
     */
    public String monthlyCostUsd() {
        double cost = ByteFormat.toGigabytes(bucketBytes) * pricePerGbMonth;
        return String.format(Locale.US, "$%.2f", cost);
    }

    /** Сколько из месячного счёта приходится на мусор, ждущий чистки. */
    public String pendingPurgeCostUsd() {
        double cost = ByteFormat.toGigabytes(pendingPurgeBytes) * pricePerGbMonth;
        return String.format(Locale.US, "$%.2f", cost);
    }
}

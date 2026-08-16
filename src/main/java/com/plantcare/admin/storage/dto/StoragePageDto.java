package com.plantcare.admin.storage.dto;

import java.util.List;

/**
 * Полная модель страницы /admin/storage (issue #101).
 *
 * @param overview      карточки-агрегаты
 * @param growth        точки графика роста (по возрастанию даты)
 * @param topUsers      топ юзеров по занятому объёму
 * @param recentUploads страница последних загрузок
 * @param page          текущая страница последних загрузок, с нуля
 * @param pageSize      размер страницы
 * @param totalUploads  всего фото в реестре (для пагинации)
 * @param elapsedMs     время сборки страницы, как на дашборде
 */
public record StoragePageDto(
        StorageOverviewDto overview,
        List<StorageDailyPointDto> growth,
        List<TopUserStorageDto> topUsers,
        List<PhotoRowDto> recentUploads,
        int page,
        int pageSize,
        long totalUploads,
        long elapsedMs
) {

    public int totalPages() {
        if (pageSize <= 0) return 1;
        return (int) Math.max(1, (totalUploads + pageSize - 1) / pageSize);
    }

    public boolean hasPrev() {
        return page > 0;
    }

    public boolean hasNext() {
        return page + 1 < totalPages();
    }

    /** Номер страницы для человека — с единицы. */
    public int pageNumber() {
        return page + 1;
    }

    /** Максимум по оси Y спарклайна; ноль защищаем, чтобы не делить на него в шаблоне. */
    public double growthPeakMb() {
        return growth.stream().mapToDouble(StorageDailyPointDto::megabytes).max().orElse(0.0);
    }

    /**
     * Высота столбика графика в процентах от пика. Считается здесь, а не
     * выражением в шаблоне: деление с защитой от нулевого пика и нижняя граница
     * в 2% (иначе почти пустой день рисуется невидимой полоской) — логика, а не
     * вёрстка, и она должна быть под тестом.
     */
    public int barPercent(StorageDailyPointDto point) {
        double peak = growthPeakMb();
        if (peak <= 0) return 2;
        return (int) Math.max(2, Math.round(point.megabytes() * 100 / peak));
    }

    /** Последняя точка графика — подпись правого края оси X. */
    public StorageDailyPointDto lastPoint() {
        return growth.isEmpty() ? null : growth.get(growth.size() - 1);
    }

    public StorageDailyPointDto firstPoint() {
        return growth.isEmpty() ? null : growth.get(0);
    }
}

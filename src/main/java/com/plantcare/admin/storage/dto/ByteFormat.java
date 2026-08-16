package com.plantcare.admin.storage.dto;

import java.util.Locale;

/**
 * Человекочитаемый размер для шаблонов /admin/storage (issue #101).
 *
 * <p>Отдельный утилитный класс, а не метод на каждом DTO: форматирование нужно
 * пяти разным record'ам, дублировать его пять раз бессмысленно.
 */
public final class ByteFormat {

    private static final long KB = 1024L;
    private static final long MB = KB * 1024L;
    private static final long GB = MB * 1024L;

    private ByteFormat() {
    }

    /** {@code 1536} → {@code "1.5 KB"}; отрицательное и ноль → {@code "0 B"}. */
    public static String humanize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < KB) return bytes + " B";
        if (bytes < MB) return format((double) bytes / KB, "KB");
        if (bytes < GB) return format((double) bytes / MB, "MB");
        return format((double) bytes / GB, "GB");
    }

    /** Размер в гигабайтах — база для прикидки расходов. */
    public static double toGigabytes(long bytes) {
        return bytes <= 0 ? 0.0 : (double) bytes / GB;
    }

    private static String format(double value, String unit) {
        return String.format(Locale.US, "%.1f %s", value, unit);
    }
}

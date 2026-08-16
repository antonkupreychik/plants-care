package com.plantcare.admin.storage.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Чистая арифметика витрины /admin/storage (issue #101): форматирование,
 * средний размер, прикидка расходов и высота столбиков графика. Всё это раньше
 * жило бы выражениями в шаблоне, где не тестируется.
 */
@DisplayName("Storage DTO — агрегаты витрины (issue #101)")
class StorageDtoTest {

    private static final long MB = 1024L * 1024L;
    private static final long GB = MB * 1024L;

    @Nested
    @DisplayName("ByteFormat")
    class Bytes {

        @Test
        @DisplayName("should_humanize_across_units")
        void should_humanize_across_units() {
            assertThat(ByteFormat.humanize(512)).isEqualTo("512 B");
            assertThat(ByteFormat.humanize(1536)).isEqualTo("1.5 KB");
            assertThat(ByteFormat.humanize(3 * MB)).isEqualTo("3.0 MB");
            assertThat(ByteFormat.humanize(2 * GB)).isEqualTo("2.0 GB");
        }

        @Test
        @DisplayName("should_return_zero_when_negative_or_empty")
        void should_return_zero_when_negative_or_empty() {
            assertThat(ByteFormat.humanize(0)).isEqualTo("0 B");
            assertThat(ByteFormat.humanize(-1)).isEqualTo("0 B");
            assertThat(ByteFormat.toGigabytes(-5)).isZero();
        }
    }

    @Nested
    @DisplayName("StorageOverviewDto")
    class Overview {

        @Test
        @DisplayName("should_estimate_monthly_cost_from_bucket_volume")
        void should_estimate_monthly_cost_from_bucket_volume() {
            // 10 ГБ в бакете по $0.015/ГБ·мес = $0.15
            var dto = new StorageOverviewDto(10 * GB, 100, 8 * GB, 80, 2 * GB, 20, 5, 0.015, 30);

            assertThat(dto.monthlyCostUsd()).isEqualTo("$0.15");
            // Мусор, ждущий чистки, стоит отдельных денег — это и есть повод чистить.
            assertThat(dto.pendingPurgeCostUsd()).isEqualTo("$0.03");
        }

        @Test
        @DisplayName("should_not_divide_by_zero_when_bucket_empty")
        void should_not_divide_by_zero_when_bucket_empty() {
            var dto = StorageOverviewDto.empty(0.015, 30);

            assertThat(dto.avgBytes()).isZero();
            assertThat(dto.avgHuman()).isEqualTo("0 B");
            assertThat(dto.monthlyCostUsd()).isEqualTo("$0.00");
        }

        @Test
        @DisplayName("should_average_over_bucket_objects")
        void should_average_over_bucket_objects() {
            var dto = new StorageOverviewDto(10 * MB, 5, 10 * MB, 5, 0, 0, 0, 0.015, 30);

            assertThat(dto.avgBytes()).isEqualTo(2 * MB);
            assertThat(dto.avgHuman()).isEqualTo("2.0 MB");
        }
    }

    @Nested
    @DisplayName("StoragePageDto")
    class Page {

        private StoragePageDto page(List<StorageDailyPointDto> growth, long total, int pageIdx) {
            return new StoragePageDto(StorageOverviewDto.empty(0.015, 30),
                    growth, List.of(), List.of(), pageIdx, 20, total, 1);
        }

        @Test
        @DisplayName("should_scale_bars_to_peak")
        void should_scale_bars_to_peak() {
            var low = new StorageDailyPointDto(LocalDate.of(2026, 1, 1), 25 * MB, 5);
            var peak = new StorageDailyPointDto(LocalDate.of(2026, 1, 2), 100 * MB, 20);
            var dto = page(List.of(low, peak), 0, 0);

            assertThat(dto.barPercent(peak)).isEqualTo(100);
            assertThat(dto.barPercent(low)).isEqualTo(25);
        }

        @Test
        @DisplayName("should_clamp_bar_to_minimum_when_peak_is_zero")
        void should_clamp_bar_to_minimum_when_peak_is_zero() {
            var zero = new StorageDailyPointDto(LocalDate.of(2026, 1, 1), 0, 0);
            var dto = page(List.of(zero), 0, 0);

            // Пик нулевой — делить не на что; столбик всё равно должен быть виден.
            assertThat(dto.barPercent(zero)).isEqualTo(2);
        }

        @Test
        @DisplayName("should_paginate_with_partial_last_page")
        void should_paginate_with_partial_last_page() {
            var dto = page(List.of(), 45, 1);   // 45 записей по 20 → 3 страницы

            assertThat(dto.totalPages()).isEqualTo(3);
            assertThat(dto.pageNumber()).isEqualTo(2);
            assertThat(dto.hasPrev()).isTrue();
            assertThat(dto.hasNext()).isTrue();
        }

        @Test
        @DisplayName("should_report_single_page_when_no_uploads")
        void should_report_single_page_when_no_uploads() {
            var dto = page(List.of(), 0, 0);

            assertThat(dto.totalPages()).isEqualTo(1);
            assertThat(dto.hasPrev()).isFalse();
            assertThat(dto.hasNext()).isFalse();
            assertThat(dto.firstPoint()).isNull();
            assertThat(dto.lastPoint()).isNull();
        }
    }

    @Nested
    @DisplayName("PhotoRowDto")
    class Row {

        private PhotoRowDto row(java.time.Instant deletedAt, java.time.Instant purgedAt) {
            return new PhotoRowDto(1L, 2L, 300L, "bob", null, null, "photos/x",
                    "image/jpeg", MB, java.time.Instant.EPOCH, deletedAt, purgedAt, null);
        }

        @Test
        @DisplayName("should_derive_status_from_deleted_and_purged_marks")
        void should_derive_status_from_deleted_and_purged_marks() {
            assertThat(row(null, null).status()).isEqualTo("active");
            assertThat(row(java.time.Instant.EPOCH, null).status()).isEqualTo("deleted");
            // purged перекрывает deleted: бинаря нет, что бы ни стояло в deleted_at
            assertThat(row(java.time.Instant.EPOCH, java.time.Instant.EPOCH).status())
                    .isEqualTo("purged");
        }

        @Test
        @DisplayName("should_label_user_without_username")
        void should_label_user_without_username() {
            var anonymous = new PhotoRowDto(1L, 2L, 300L, null, null, null, "photos/x",
                    "image/jpeg", MB, java.time.Instant.EPOCH, null, null, null);

            assertThat(anonymous.userLabel()).isEqualTo("(300)");
            assertThat(row(null, null).userLabel()).isEqualTo("@bob (300)");
        }
    }
}

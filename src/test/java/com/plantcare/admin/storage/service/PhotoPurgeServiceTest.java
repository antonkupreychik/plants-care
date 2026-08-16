package com.plantcare.admin.storage.service;

import com.plantcare.admin.storage.service.AdminPhotoService.PurgeCandidate;
import com.plantcare.core.service.PhotoStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Физическая чистка бакета (issue #101).
 *
 * <p>Проверяем именно опасную часть: порядок «S3 → отметка», что падение S3 не
 * приводит к отметке (иначе объект остался бы в бакете навсегда, помеченный как
 * вычищенный) и что прогон идемпотентен.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PhotoPurgeService — физическая чистка бакета (issue #101)")
class PhotoPurgeServiceTest {

    @Mock private AdminPhotoService adminPhotoService;
    @Mock private PhotoStorageService photoStorageService;

    @InjectMocks private PhotoPurgeService service;

    @Test
    @DisplayName("should_delete_object_then_mark_purged_when_candidate_expired")
    void should_delete_object_then_mark_purged_when_candidate_expired() {
        when(adminPhotoService.findPurgeCandidates())
                .thenReturn(List.of(new PurgeCandidate(7L, "photos/abc", 42L)));
        when(adminPhotoService.markPurged(7L)).thenReturn(true);

        PhotoPurgeService.PurgeResult result = service.purgeExpired();

        assertThat(result.purged()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        // Порядок критичен: сначала объект уходит из бакета, только потом отметка.
        InOrder order = inOrder(photoStorageService, adminPhotoService);
        order.verify(photoStorageService).delete("photos/abc");
        order.verify(adminPhotoService).markPurged(7L);
    }

    @Test
    @DisplayName("should_not_mark_purged_when_s3_delete_fails")
    void should_not_mark_purged_when_s3_delete_fails() {
        when(adminPhotoService.findPurgeCandidates())
                .thenReturn(List.of(new PurgeCandidate(9L, "photos/boom", 1L)));
        doThrow(new RuntimeException("S3 unavailable")).when(photoStorageService).delete("photos/boom");

        PhotoPurgeService.PurgeResult result = service.purgeExpired();

        assertThat(result.purged()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        // Не отмечено — фото попадёт в следующий прогон, объект не потеряется.
        verify(adminPhotoService, never()).markPurged(anyLong());
    }

    @Test
    @DisplayName("should_continue_batch_when_one_object_fails")
    void should_continue_batch_when_one_object_fails() {
        when(adminPhotoService.findPurgeCandidates()).thenReturn(List.of(
                new PurgeCandidate(1L, "photos/ok-1", 1L),
                new PurgeCandidate(2L, "photos/bad", 1L),
                new PurgeCandidate(3L, "photos/ok-2", 1L)));
        // Стабим delete(anyString()) целиком, а не только «плохой» ключ: при
        // strict-stubs вызов с неотстабленным аргументом сам кидает
        // PotentialStubbingProblem, и catch в сервисе засчитал бы его за сбой S3.
        doAnswer(invocation -> {
            if ("photos/bad".equals(invocation.getArgument(0))) {
                throw new RuntimeException("boom");
            }
            return null;
        }).when(photoStorageService).delete(anyString());
        when(adminPhotoService.markPurged(anyLong())).thenReturn(true);

        PhotoPurgeService.PurgeResult result = service.purgeExpired();

        assertThat(result.purged()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.total()).isEqualTo(3);
        verify(adminPhotoService).markPurged(1L);
        verify(adminPhotoService).markPurged(3L);
        verify(adminPhotoService, never()).markPurged(2L);
    }

    @Test
    @DisplayName("should_be_noop_when_nothing_expired")
    void should_be_noop_when_nothing_expired() {
        when(adminPhotoService.findPurgeCandidates()).thenReturn(List.of());

        PhotoPurgeService.PurgeResult result = service.purgeExpired();

        assertThat(result.total()).isZero();
        verify(photoStorageService, never()).delete(anyString());
    }

    @Test
    @DisplayName("should_report_success_when_already_marked_purged")
    void should_report_success_when_already_marked_purged() {
        // Повторный прогон по тому же кандидату: S3 DeleteObject идемпотентен,
        // markPurged вернёт false (purged_at уже стоит) — это не ошибка.
        when(adminPhotoService.findPurgeCandidates())
                .thenReturn(List.of(new PurgeCandidate(5L, "photos/dup", 1L)));
        when(adminPhotoService.markPurged(5L)).thenReturn(false);

        PhotoPurgeService.PurgeResult result = service.purgeExpired();

        assertThat(result.purged()).isEqualTo(1);
        assertThat(result.failed()).isZero();
    }
}

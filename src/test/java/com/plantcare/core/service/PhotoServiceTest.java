package com.plantcare.core.service;

import com.plantcare.core.config.S3StorageProperties;
import com.plantcare.core.domain.Photo;
import com.plantcare.core.repository.PhotoRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тест {@link PhotoService} (issue #90): валидация типа/размера, soft-delete,
 * 404 на чужое/удалённое. Хранилище и репозиторий — моки.
 */
@ExtendWith(MockitoExtension.class)
class PhotoServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-08T12:00:00Z");

    @Mock
    private PhotoStorageService photoStorageService;

    @Mock
    private PhotoRepository photoRepository;

    private PhotoService photoService;

    @BeforeEach
    void setUp() {
        S3StorageProperties properties = new S3StorageProperties(
                "test-bucket", "https://s3.example.com", "us-east-1",
                10L, Duration.ofMinutes(15), "photos/");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        photoService = new PhotoService(photoStorageService, photoRepository, properties, clock);
    }

    @Test
    void should_store_and_persist_when_valid_image() {
        // arrange
        byte[] bytes = new byte[]{1, 2, 3};
        when(photoStorageService.put(bytes, "image/jpeg"))
                .thenReturn(new StoredPhoto("photos/abc", 3L));
        when(photoRepository.save(any(Photo.class))).thenAnswer(inv -> inv.getArgument(0));

        // act
        Photo photo = photoService.upload(1L, bytes, "image/jpeg");

        // assert
        assertThat(photo.getStorageKey()).isEqualTo("photos/abc");
        assertThat(photo.getContentType()).isEqualTo("image/jpeg");
        assertThat(photo.getSizeBytes()).isEqualTo(3L);
        assertThat(photo.getUserId()).isEqualTo(1L);
        assertThat(photo.getCreatedAt()).isEqualTo(NOW);
        verify(photoStorageService).put(bytes, "image/jpeg");
        verify(photoRepository).save(any(Photo.class));
    }

    @Test
    void should_reject_non_image_content_type_with_invalid_photo() {
        // arrange
        byte[] bytes = new byte[]{1, 2, 3};

        // act + assert
        assertThatThrownBy(() -> photoService.upload(1L, bytes, "application/pdf"))
                .isInstanceOf(InvalidPhotoException.class);

        verify(photoStorageService, never()).put(any(), any());
        verify(photoRepository, never()).save(any());
    }

    @Test
    void should_reject_empty_file_with_invalid_photo() {
        // act + assert
        assertThatThrownBy(() -> photoService.upload(1L, new byte[0], "image/png"))
                .isInstanceOf(InvalidPhotoException.class);
    }

    @Test
    void should_reject_oversized_file_with_too_large() {
        // arrange — лимит 10 байт, шлём 11
        byte[] bytes = new byte[11];

        // act + assert
        assertThatThrownBy(() -> photoService.upload(1L, bytes, "image/png"))
                .isInstanceOf(PhotoTooLargeException.class);

        verify(photoStorageService, never()).put(any(), any());
    }

    @Test
    void should_return_presigned_url_for_active_photo() throws Exception {
        // arrange
        Photo photo = new Photo("photos/abc", "image/png", 3L, 1L, NOW);
        URL url = new URL("https://s3.example.com/test-bucket/photos/abc?sig=1");
        when(photoRepository.findByIdAndUserIdAndDeletedAtIsNull(42L, 1L)).thenReturn(Optional.of(photo));
        when(photoStorageService.presignedGetUrl("photos/abc")).thenReturn(url);

        // act
        URL result = photoService.presignedUrl(1L, 42L);

        // assert
        assertThat(result).isEqualTo(url);
    }

    @Test
    void should_throw_not_found_when_presigning_missing_or_foreign_photo() {
        // arrange
        when(photoRepository.findByIdAndUserIdAndDeletedAtIsNull(eq(99L), eq(1L)))
                .thenReturn(Optional.empty());

        // act + assert
        assertThatThrownBy(() -> photoService.presignedUrl(1L, 99L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void should_soft_delete_active_photo() {
        // arrange
        Photo photo = new Photo("photos/abc", "image/png", 3L, 1L, NOW);
        when(photoRepository.findByIdAndUserIdAndDeletedAtIsNull(42L, 1L)).thenReturn(Optional.of(photo));

        // act
        photoService.softDelete(1L, 42L);

        // assert
        assertThat(photo.isDeleted()).isTrue();
        assertThat(photo.getDeletedAt()).isEqualTo(NOW);
    }

    @Test
    void should_throw_not_found_when_deleting_missing_or_already_deleted_photo() {
        // arrange — уже удалённое не находится фильтром deleted_at IS NULL
        when(photoRepository.findByIdAndUserIdAndDeletedAtIsNull(99L, 1L)).thenReturn(Optional.empty());

        // act + assert
        assertThatThrownBy(() -> photoService.softDelete(1L, 99L))
                .isInstanceOf(EntityNotFoundException.class);
    }
}

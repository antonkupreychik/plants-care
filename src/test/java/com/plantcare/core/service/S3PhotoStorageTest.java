package com.plantcare.core.service;

import com.plantcare.core.config.S3StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URL;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тест {@link S3PhotoStorage} с моками S3-клиента и пресайнера (issue #90).
 * LocalStack намеренно не поднимаем — проверяем построение запросов к SDK.
 */
@ExtendWith(MockitoExtension.class)
class S3PhotoStorageTest {

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner s3Presigner;

    private S3PhotoStorage storage;

    @BeforeEach
    void setUp() {
        S3StorageProperties properties = new S3StorageProperties(
                "test-bucket", "https://s3.example.com", "us-east-1",
                5_242_880L, Duration.ofMinutes(15), "photos/");
        storage = new S3PhotoStorage(s3Client, s3Presigner, properties);
    }

    @Test
    void should_put_object_with_namespaced_key_and_metadata() {
        // arrange
        byte[] bytes = new byte[]{1, 2, 3, 4};

        // act
        StoredPhoto stored = storage.put(bytes, "image/png");

        // assert
        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));

        PutObjectRequest request = requestCaptor.getValue();
        assertThat(request.bucket()).isEqualTo("test-bucket");
        assertThat(request.key()).startsWith("photos/");
        assertThat(request.contentType()).isEqualTo("image/png");
        assertThat(request.contentLength()).isEqualTo(4L);

        assertThat(stored.storageKey()).startsWith("photos/");
        assertThat(stored.sizeBytes()).isEqualTo(4L);
    }

    @Test
    void should_return_presigned_url_for_key() throws Exception {
        // arrange
        URL expectedUrl = new URL("https://s3.example.com/test-bucket/photos/abc?X-Amz-Signature=xyz");
        PresignedGetObjectRequest presigned = org.mockito.Mockito.mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(expectedUrl);
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        // act
        URL url = storage.presignedGetUrl("photos/abc");

        // assert
        assertThat(url).isEqualTo(expectedUrl);
        verify(s3Presigner).presignGetObject(any(GetObjectPresignRequest.class));
    }

    @Test
    void should_delete_object_by_key() {
        // act
        storage.delete("photos/abc");

        // assert
        ArgumentCaptor<DeleteObjectRequest> requestCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(requestCaptor.capture());

        DeleteObjectRequest request = requestCaptor.getValue();
        assertThat(request.bucket()).isEqualTo("test-bucket");
        assertThat(request.key()).isEqualTo("photos/abc");
    }
}

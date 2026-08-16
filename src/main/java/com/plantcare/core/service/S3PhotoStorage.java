package com.plantcare.core.service;

import com.plantcare.core.config.S3StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URL;
import java.util.UUID;

/**
 * Реализация {@link PhotoStorageService} поверх AWS SDK v2 для S3-совместимого
 * хранилища (issue #90, Slice A).
 *
 * <p>Ключи namespaced: {@code <keyPrefix><uuid>} (например, {@code photos/<uuid>}).
 * GET отдаётся как пресайн-ссылка с TTL из {@link S3StorageProperties}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S3PhotoStorage implements PhotoStorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3StorageProperties properties;

    @Override
    public StoredPhoto put(byte[] bytes, String contentType) {
        String key = buildKey();

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .contentType(contentType)
                .contentLength((long) bytes.length)
                .build();

        s3Client.putObject(request, RequestBody.fromBytes(bytes));
        log.info("Stored photo object key={} bytes={} contentType={}", key, bytes.length, contentType);

        return new StoredPhoto(key, bytes.length);
    }

    @Override
    public URL presignedGetUrl(String storageKey) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(storageKey)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(properties.getPresignTtl())
                .getObjectRequest(getObjectRequest)
                .build();

        return s3Presigner.presignGetObject(presignRequest).url();
    }

    @Override
    public void delete(String storageKey) {
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(storageKey)
                .build();

        s3Client.deleteObject(request);
        log.info("Deleted photo object key={}", storageKey);
    }

    private String buildKey() {
        String prefix = properties.getKeyPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "photos/";
        } else if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        return prefix + UUID.randomUUID();
    }
}

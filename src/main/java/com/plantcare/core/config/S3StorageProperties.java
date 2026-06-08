package com.plantcare.core.config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Конфигурация S3-совместимого object storage для фото (issue #90, Slice A).
 *
 * <p>Хранилище — Railway (S3-совместимое), НЕ настоящий AWS. Поэтому критичны
 * {@code endpoint} (endpointOverride) и path-style адресация на стороне клиента.
 * Креды ({@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY}) сюда НЕ
 * попадают — их читает напрямую {@code DefaultCredentialsProvider} из окружения.
 *
 * <ul>
 *   <li>{@code bucket} — имя бакета ({@code AWS_S3_BUCKET_NAME}).</li>
 *   <li>{@code endpoint} — URL S3-эндпоинта ({@code AWS_ENDPOINT_URL}); пустой =
 *       не переопределять (дефолтный AWS-резолвинг).</li>
 *   <li>{@code region} — регион ({@code AWS_DEFAULT_REGION}); SDK читает его и сам,
 *       но клиенты собираем явно, поэтому дублируем здесь.</li>
 *   <li>{@code maxUploadBytes} — верхний предел размера загружаемого фото (~5 МБ).</li>
 *   <li>{@code presignTtl} — TTL пресайн-ссылки на GET.</li>
 *   <li>{@code keyPrefix} — namespace-префикс ключей в бакете.</li>
 * </ul>
 */
@Getter
@AllArgsConstructor
@ConfigurationProperties(prefix = "app.storage.s3")
public class S3StorageProperties {

    /** Имя бакета. */
    private final String bucket;

    /** Endpoint S3-совместимого хранилища. Пустая строка = не переопределять. */
    private final String endpoint;

    /** Регион. */
    private final String region;

    /** Максимальный размер загружаемого файла в байтах (дефолт ~5 МБ). */
    private final long maxUploadBytes;

    /** TTL пресайн-ссылки на скачивание. */
    private final Duration presignTtl;

    /** Namespace-префикс ключей в бакете (например, {@code photos/}). */
    private final String keyPrefix;

    /** {@code true}, если endpoint задан и его надо переопределить на клиенте. */
    public boolean hasEndpointOverride() {
        return endpoint != null && !endpoint.isBlank();
    }
}

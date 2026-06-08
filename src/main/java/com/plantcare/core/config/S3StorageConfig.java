package com.plantcare.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Бины S3-клиента и пресайнера для object storage фото (issue #90, Slice A).
 *
 * <p>Хранилище — S3-совместимое (Railway), НЕ настоящий AWS, поэтому:
 * <ul>
 *   <li>{@code forcePathStyle(true)} — path-style адресация (виртуальные хосты
 *       бакета у не-AWS провайдеров обычно не настроены);</li>
 *   <li>{@code endpointOverride} — если задан {@code AWS_ENDPOINT_URL};</li>
 *   <li>{@link DefaultCredentialsProvider} — креды читаются из окружения
 *       ({@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY}), не хардкодим;</li>
 *   <li>{@link UrlConnectionHttpClient} — лёгкий синхронный HTTP-клиент вместо
 *       netty-async (netty-nio-client исключён из зависимости s3 в pom).</li>
 * </ul>
 *
 * <p>{@link S3Presigner} HTTP-вызовов не делает (только подписывает URL локально),
 * поэтому httpClient ему не передаём.
 */
@Configuration
public class S3StorageConfig {

    @Bean
    public SdkHttpClient s3HttpClient() {
        return UrlConnectionHttpClient.builder().build();
    }

    @Bean
    public S3Client s3Client(S3StorageProperties properties, SdkHttpClient s3HttpClient) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .httpClient(s3HttpClient)
                .forcePathStyle(true);

        if (properties.hasEndpointOverride()) {
            builder = builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        return builder.build();
    }

    @Bean
    public S3Presigner s3Presigner(S3StorageProperties properties) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create());

        if (properties.hasEndpointOverride()) {
            builder = builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        // forcePathStyle через serviceConfiguration: на S3Presigner.Builder нет
        // прямого forcePathStyle(...), путь задаётся через S3Configuration.
        return builder
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }
}

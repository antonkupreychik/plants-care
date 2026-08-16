package com.plantcare.core.service;

/**
 * Загружаемое фото превышает лимит размера (issue #90, Slice A).
 * Маппится в HTTP 413 Payload Too Large.
 */
public class PhotoTooLargeException extends RuntimeException {

    public PhotoTooLargeException(long sizeBytes, long maxBytes) {
        super("Photo too large: " + sizeBytes + " bytes (max " + maxBytes + ")");
    }
}

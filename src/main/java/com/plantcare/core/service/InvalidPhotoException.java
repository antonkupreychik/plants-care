package com.plantcare.core.service;

/**
 * Загружаемый файл не является валидным фото (не {@code image/*} или пустой) —
 * issue #90, Slice A. Маппится в HTTP 400 Bad Request.
 */
public class InvalidPhotoException extends RuntimeException {

    public InvalidPhotoException(String message) {
        super(message);
    }
}

package com.plantcare.core.domain;

/**
 * Мобильная платформа устройства (issue #175, ADR-014).
 * Хранится в БД как строка в верхнем регистре ({@code @Enumerated(STRING)}).
 */
public enum DevicePlatform {
    IOS,
    ANDROID
}

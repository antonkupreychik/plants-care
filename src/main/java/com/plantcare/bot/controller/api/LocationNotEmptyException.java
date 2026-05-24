package com.plantcare.bot.controller.api;

/**
 * Бросается контроллером локаций, когда пользователь пытается удалить локацию
 * с растениями, не указав {@code targetLocationId}. Маппится {@link ApiExceptionHandler}
 * в 400 с {@code code=LOCATION_NOT_EMPTY}.
 */
public class LocationNotEmptyException extends RuntimeException {

    public LocationNotEmptyException(String message) {
        super(message);
    }
}

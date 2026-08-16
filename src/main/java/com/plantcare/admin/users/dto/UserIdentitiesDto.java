package com.plantcare.admin.users.dto;

/**
 * Сырые идентификаторы юзера из таблицы {@code users} (issue #93).
 * Внутренний тип admin-слоя: наружу отдаётся уже замаскированный
 * {@link AuthProviderDto}, поэтому этот record не должен попадать в модель
 * шаблона и не должен логироваться целиком.
 *
 * @param guestDeviceId UUID гостевого устройства. Не провайдер в смысле issue #93
 *                      (разрывать его отдельной кнопкой нельзя), но учитывается
 *                      в проверке «останется ли юзеру чем залогиниться».
 */
public record UserIdentitiesDto(
        long userId,
        Long telegramChatId,
        String email,
        boolean emailVerified,
        String appleSubject,
        String googleSubject,
        String guestDeviceId
) {

    public boolean hasGuestDevice() {
        return guestDeviceId != null && !guestDeviceId.isBlank();
    }
}

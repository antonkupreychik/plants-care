package com.plantcare.api;

/**
 * Бросается когда пользователь пытается удалить комнату,
 * в которой есть активные растения. Маппится {@link ApiExceptionHandler}
 * в 409 с {@code code=ROOM_NOT_EMPTY}.
 */
public class RoomNotEmptyException extends RuntimeException {

    public RoomNotEmptyException(String message) {
        super(message);
    }

    public static RoomNotEmptyException forRoom(Long roomId, long plantCount) {
        return new RoomNotEmptyException(
                "Комната содержит " + plantCount + " активных растений. Перенесите их перед удалением."
        );
    }
}

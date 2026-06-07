package com.plantcare.api.v1;

import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.RoomNotEmptyException;
import com.plantcare.api.generated.RoomsApi;
import com.plantcare.api.generated.model.RoomCreateRequest;
import com.plantcare.api.generated.model.RoomDto;
import com.plantcare.api.generated.model.RoomUpdateRequest;
import com.plantcare.core.domain.Room;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.RoomService;
import com.plantcare.core.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.util.List;

/**
 * REST API для управления комнатами внутри локации (issue #283).
 *
 * <p>Документация и маппинг живут в сгенерированном {@link RoomsApi}
 * (см. openapi/resources/rooms.yaml).
 */
@RestController
@RequiredArgsConstructor
public class RoomController implements RoomsApi {

    private final RoomService roomService;
    private final UserService userService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public List<RoomDto> listRooms(Long locationId) {
        Long userId = currentUserProvider.currentUserId();
        return roomService.getRoomsInLocation(userId, locationId)
                .stream()
                .map(RoomController::toDto)
                .toList();
    }

    @Override
    public RoomDto createRoom(Long locationId, RoomCreateRequest request) {
        User user = userService.getByIdOrThrow(currentUserProvider.currentUserId());
        Room room = roomService.createRoom(user, locationId, request.getName(), request.getDisplayOrder());
        return toDto(room);
    }

    @Override
    public RoomDto updateRoom(Long id, RoomUpdateRequest request) {
        Long userId = currentUserProvider.currentUserId();
        Room room = roomService.updateRoom(userId, id, request.getName(), request.getDisplayOrder());
        return toDto(room);
    }

    @Override
    public void deleteRoom(Long id) {
        Long userId = currentUserProvider.currentUserId();
        try {
            roomService.deleteRoom(userId, id);
        } catch (IllegalStateException e) {
            throw new RoomNotEmptyException(e.getMessage());
        }
    }

    private static RoomDto toDto(Room room) {
        Long locationId = room.getLocation() != null ? room.getLocation().getId() : null;
        RoomDto dto = new RoomDto()
                .id(room.getId())
                .name(room.getName())
                .locationId(locationId)
                .displayOrder(room.getDisplayOrder());
        if (room.getCreatedAt() != null) {
            dto.createdAt(room.getCreatedAt().atOffset(ZoneOffset.UTC));
        }
        return dto;
    }
}

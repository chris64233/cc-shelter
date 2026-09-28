package com.chris64233.cc.shelter.web.dto;

import com.chris64233.cc.shelter.domain.Room;

public record RoomResponse(Long roomId,
                           int roomNumber,
                           int bedCount,
                           boolean accessible,
                           int occupied,
                           int reservedBeds,
                           int remaining,
                           int available) {

    public static RoomResponse of(Room room) {
        return new RoomResponse(room.getId(), room.getRoomNumber(), room.getBedCount(),
                room.isAccessible(), room.getOccupied(), room.getReservedBeds(),
                room.remainingBeds(), room.availableBeds());
    }
}

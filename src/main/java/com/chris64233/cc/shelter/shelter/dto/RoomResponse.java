package com.chris64233.cc.shelter.shelter.dto;

import com.chris64233.cc.shelter.shelter.Room;

public record RoomResponse(
        Long id,
        Long shelterId,
        int roomNumber,
        int bedCapacity,
        boolean accessible,
        int occupiedBeds,
        int availableBeds) {

    public static RoomResponse from(Room room) {
        return new RoomResponse(
                room.getId(),
                room.getShelter().getId(),
                room.getRoomNumber(),
                room.getBedCapacity(),
                room.isAccessible(),
                room.getOccupiedBeds(),
                room.getAvailableBeds());
    }
}

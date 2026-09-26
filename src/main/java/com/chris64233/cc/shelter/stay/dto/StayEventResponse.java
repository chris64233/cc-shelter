package com.chris64233.cc.shelter.stay.dto;

import com.chris64233.cc.shelter.stay.StayEvent;

import java.time.Instant;

public record StayEventResponse(
        Long id,
        String householdNumber,
        String type,
        Long fromShelterId,
        Long fromRoomId,
        Long toShelterId,
        Long toRoomId,
        int memberCount,
        Instant occurredAt) {

    public static StayEventResponse from(StayEvent event) {
        return new StayEventResponse(
                event.getId(),
                event.getHouseholdNumber(),
                event.getType().name(),
                event.getFromShelterId(),
                event.getFromRoomId(),
                event.getToShelterId(),
                event.getToRoomId(),
                event.getMemberCount(),
                event.getOccurredAt());
    }
}

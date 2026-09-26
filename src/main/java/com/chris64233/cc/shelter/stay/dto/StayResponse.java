package com.chris64233.cc.shelter.stay.dto;

import com.chris64233.cc.shelter.stay.Stay;

import java.time.Instant;

public record StayResponse(
        Long stayId,
        String householdNumber,
        Long shelterId,
        Long roomId,
        int roomNumber,
        int memberCount,
        String status,
        Instant checkedInAt) {

    public static StayResponse from(Stay stay) {
        return new StayResponse(
                stay.getId(),
                stay.getHouseholdNumber(),
                stay.getShelter().getId(),
                stay.getRoom().getId(),
                stay.getRoom().getRoomNumber(),
                stay.getMemberCount(),
                stay.getStatus().name(),
                stay.getCheckedInAt());
    }
}

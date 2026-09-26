package com.chris64233.cc.shelter.web.dto;

import com.chris64233.cc.shelter.domain.Stay;
import java.time.Instant;

public record StayResponse(Long stayId,
                           String householdNo,
                           Long shelterId,
                           Long roomId,
                           int roomNumber,
                           int memberCount,
                           String status,
                           Instant since) {

    public static StayResponse of(Stay stay) {
        return new StayResponse(stay.getId(), stay.getHousehold().getHouseholdNo(),
                stay.getShelter().getId(), stay.getRoom().getId(), stay.getRoom().getRoomNumber(),
                stay.getMemberCount(), stay.getStatus().name(), stay.getCreatedAt());
    }
}

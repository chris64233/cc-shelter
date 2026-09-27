package com.chris64233.cc.shelter.web.dto;

import com.chris64233.cc.shelter.domain.StayEvent;
import java.time.Instant;

public record StayEventResponse(Long eventId,
                                Long stayId,
                                String householdNo,
                                String type,
                                Long fromShelterId,
                                Long fromRoomId,
                                Integer fromRoomNumber,
                                Long toShelterId,
                                Long toRoomId,
                                Integer toRoomNumber,
                                int memberCount,
                                String mergeNo,
                                Instant occurredAt) {

    public static StayEventResponse of(StayEvent event) {
        return new StayEventResponse(event.getId(), event.getStayId(), event.getHouseholdNo(),
                event.getType().name(), event.getFromShelterId(), event.getFromRoomId(),
                event.getFromRoomNumber(), event.getToShelterId(), event.getToRoomId(),
                event.getToRoomNumber(), event.getMemberCount(), event.getMergeNo(),
                event.getOccurredAt());
    }
}
